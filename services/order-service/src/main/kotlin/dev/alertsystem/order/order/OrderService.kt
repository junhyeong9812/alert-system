package dev.alertsystem.order.order

import dev.alertsystem.order.alerting.AlertReporter
import dev.alertsystem.order.support.Crash
import dev.alertsystem.order.support.RequestContext
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

data class PlaceOrder(val productId: Long, val quantity: Int, val address: String)

data class OrderResult(val orderId: Long, val status: String, val reason: String? = null)

// 동기 오케스트레이션 (모드 A): order → product 재고 차감 → shipping 배송 생성, 배송 실패 시 재고 복원(보상).
// SG-01 기준선에서는 각 서비스가 자기 실패만 알림으로 보낸다 — order는 하위 실패를 알리지 않는다.
@Service
class OrderService(
    private val jdbc: JdbcClient,
    private val product: ProductClient,
    private val shipping: ShippingClient,
    private val alerts: AlertReporter,
) {
    fun place(cmd: PlaceOrder): Pair<HttpStatus, OrderResult> {
        val ctx = RequestContext.current()
        val orderId = jdbc.sql(
            """
            insert into orders(product_id, quantity, address, status, created_at, updated_at)
            values (?, ?, ?, 'PENDING', now(), now()) returning id
            """.trimIndent(),
        ).params(cmd.productId, cmd.quantity, cmd.address).query(Long::class.java).single()

        // 자기 실패: 실패 기록 커밋 → (crash 시 여기서 종료) → 알림
        if (ctx.has("biz")) {
            finish(orderId, "FAILED", "ORDER_LIMIT_EXCEEDED")
            alerts.report("F1", "ORDER_LIMIT_EXCEEDED", "warn", "order $orderId exceeds order limit")
            return HttpStatus.UNPROCESSABLE_CONTENT to OrderResult(orderId, "FAILED", "ORDER_LIMIT_EXCEEDED")
        }
        if (ctx.has("tech") || ctx.has("crash-after-commit")) {
            finish(orderId, "FAILED", "TECH_ERROR")
            if (ctx.has("crash-after-commit")) Crash.now()
            alerts.report("F2", "TECH_ERROR", "warn", "order $orderId technical failure")
            return HttpStatus.INTERNAL_SERVER_ERROR to OrderResult(orderId, "FAILED", "TECH_ERROR")
        }

        when (val r = product.reserve(orderId, cmd.productId, cmd.quantity)) {
            CallResult.Ok -> Unit
            is CallResult.Rejected -> return fail(orderId, HttpStatus.CONFLICT, r.code)
            is CallResult.Failed -> return fail(orderId, HttpStatus.BAD_GATEWAY, "PRODUCT_${r.reason}")
        }

        val shipped = shipping.create(orderId, cmd.address)
        if (shipped == CallResult.Ok) {
            finish(orderId, "CONFIRMED", null)
            return HttpStatus.CREATED to OrderResult(orderId, "CONFIRMED")
        }

        val reason = when (shipped) {
            is CallResult.Rejected -> shipped.code
            is CallResult.Failed -> "SHIPPING_${shipped.reason}"
            CallResult.Ok -> error("unreachable")
        }
        if (product.release(orderId) != CallResult.Ok) {
            finish(orderId, "COMPENSATION_FAILED", reason)
            return HttpStatus.BAD_GATEWAY to OrderResult(orderId, "COMPENSATION_FAILED", reason)
        }
        val status = if (shipped is CallResult.Rejected) HttpStatus.UNPROCESSABLE_CONTENT else HttpStatus.BAD_GATEWAY
        return fail(orderId, status, reason)
    }

    private fun fail(orderId: Long, status: HttpStatus, reason: String): Pair<HttpStatus, OrderResult> {
        finish(orderId, "FAILED", reason)
        return status to OrderResult(orderId, "FAILED", reason)
    }

    private fun finish(orderId: Long, status: String, reason: String?) {
        jdbc.sql("update orders set status = ?, failure_reason = ?, updated_at = now() where id = ?")
            .param(status).param(reason).param(orderId).update()
    }
}

@RestController
class OrderController(private val service: OrderService) {
    @PostMapping("/orders")
    fun place(@RequestBody cmd: PlaceOrder): ResponseEntity<OrderResult> {
        val (status, result) = service.place(cmd)
        return ResponseEntity.status(status).body(result)
    }
}
