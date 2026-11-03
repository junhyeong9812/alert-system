package dev.alertsystem.product.stock

import dev.alertsystem.product.alerting.AlertReporter
import dev.alertsystem.product.support.Crash
import dev.alertsystem.product.support.ErrorBody
import dev.alertsystem.product.support.RequestContext
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

data class Reserve(val orderId: Long, val productId: Long, val quantity: Int)
data class Release(val orderId: Long)

@Service
class StockStore(private val jdbc: JdbcClient) {
    fun record(orderId: Long, productId: Long, quantity: Int, status: String, reason: String?) {
        jdbc.sql(
            """
            insert into reservations(order_id, product_id, quantity, status, reason, created_at)
            values (?, ?, ?, ?, ?, now()) on conflict (order_id) do nothing
            """.trimIndent(),
        ).param(orderId).param(productId).param(quantity).param(status).param(reason).update()
    }

    // 재고가 충분하면 차감 + RESERVED 기록, 아니면 false (트랜잭션 하나).
    @Transactional
    fun tryReserve(cmd: Reserve): Boolean {
        val updated = jdbc.sql("update products set stock = stock - ? where id = ? and stock >= ?")
            .params(cmd.quantity, cmd.productId, cmd.quantity).update()
        if (updated == 0) return false
        record(cmd.orderId, cmd.productId, cmd.quantity, "RESERVED", null)
        return true
    }

    // 보상: RESERVED 예약을 RELEASED로 바꾸고 재고 복원. 예약이 없거나 이미 복원됐으면 아무것도 안 함 (멱등).
    @Transactional
    fun release(orderId: Long) {
        val row = jdbc.sql(
            "update reservations set status = 'RELEASED' where order_id = ? and status = 'RESERVED' returning product_id, quantity",
        ).param(orderId).query { rs, _ -> rs.getLong(1) to rs.getInt(2) }.optional().orElse(null) ?: return
        jdbc.sql("update products set stock = stock + ? where id = ?").params(row.second, row.first).update()
    }
}

@RestController
class StockController(private val store: StockStore, private val alerts: AlertReporter) {
    @PostMapping("/stock/reserve")
    fun reserve(@RequestBody cmd: Reserve): ResponseEntity<Any> {
        val ctx = RequestContext.current()
        // 실패 기록 커밋 → (crash 시 여기서 종료) → 알림
        if (ctx.has("tech") || ctx.has("crash-after-commit")) {
            store.record(cmd.orderId, cmd.productId, cmd.quantity, "FAILED", "TECH_ERROR")
            if (ctx.has("crash-after-commit")) Crash.now()
            alerts.report("F2", "TECH_ERROR", "warn", "reserve failed for order ${cmd.orderId}")
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErrorBody("TECH_ERROR"))
        }
        if (ctx.has("biz") || !store.tryReserve(cmd)) {
            store.record(cmd.orderId, cmd.productId, cmd.quantity, "REJECTED", "OUT_OF_STOCK")
            alerts.report("F1", "OUT_OF_STOCK", "warn", "product ${cmd.productId} out of stock for order ${cmd.orderId}")
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorBody("OUT_OF_STOCK"))
        }
        return ResponseEntity.ok(mapOf("status" to "RESERVED"))
    }

    @PostMapping("/stock/release")
    fun release(@RequestBody cmd: Release): ResponseEntity<Any> {
        if (RequestContext.current().has("compensation-fail")) {
            alerts.report("F5", "COMPENSATION_FAILED", "critical", "stock release failed for order ${cmd.orderId}")
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErrorBody("COMPENSATION_FAILED"))
        }
        store.release(cmd.orderId)
        return ResponseEntity.ok(mapOf("status" to "RELEASED"))
    }
}
