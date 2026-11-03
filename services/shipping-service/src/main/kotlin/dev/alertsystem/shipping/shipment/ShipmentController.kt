package dev.alertsystem.shipping.shipment

import dev.alertsystem.shipping.alerting.AlertReporter
import dev.alertsystem.shipping.support.Crash
import dev.alertsystem.shipping.support.ErrorBody
import dev.alertsystem.shipping.support.RequestContext
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

data class CreateShipment(val orderId: Long, val address: String)

@RestController
class ShipmentController(private val jdbc: JdbcClient, private val alerts: AlertReporter) {
    @PostMapping("/shipments")
    fun create(@RequestBody cmd: CreateShipment): ResponseEntity<Any> {
        val ctx = RequestContext.current()
        // 실패 기록 커밋 → (crash 시 여기서 종료) → 알림
        if (ctx.has("tech") || ctx.has("crash-after-commit")) {
            record(cmd, "FAILED", "TECH_ERROR")
            if (ctx.has("crash-after-commit")) Crash.now()
            alerts.report("F2", "TECH_ERROR", "warn", "shipment creation failed for order ${cmd.orderId}")
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErrorBody("TECH_ERROR"))
        }
        if (ctx.has("biz")) {
            record(cmd, "FAILED", "ADDRESS_INVALID")
            alerts.report("F1", "ADDRESS_INVALID", "warn", "invalid address for order ${cmd.orderId}")
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(ErrorBody("ADDRESS_INVALID"))
        }
        record(cmd, "CREATED", null)
        return ResponseEntity.status(HttpStatus.CREATED).body(mapOf("status" to "CREATED"))
    }

    private fun record(cmd: CreateShipment, status: String, reason: String?) {
        jdbc.sql(
            """
            insert into shipments(order_id, address, status, reason, created_at)
            values (?, ?, ?, ?, now()) on conflict (order_id) do nothing
            """.trimIndent(),
        ).param(cmd.orderId).param(cmd.address).param(status).param(reason).update()
    }
}
