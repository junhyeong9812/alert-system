package dev.alertsystem.shipping.shipment

import dev.alertsystem.shipping.PostgresTestConfig
import dev.alertsystem.shipping.RecordingNotificationClient
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

private const val BODY = """{"orderId": 42, "address": "Seoul"}"""

abstract class ShipmentTestBase(
    protected val mvc: MockMvc,
    protected val notifications: RecordingNotificationClient,
    protected val jdbc: JdbcClient,
) {
    @BeforeEach
    fun reset() {
        notifications.sent.clear()
        jdbc.sql("truncate shipments, alert_status").update()
    }

    protected fun create(fault: String?) = mvc.post("/shipments") {
        contentType = MediaType.APPLICATION_JSON
        content = BODY
        header("X-Test-Run-Id", "run-1")
        header("X-Request-Id", "req-1")
        fault?.let { header("X-Fault", it) }
    }

    protected fun shipmentStatus() =
        jdbc.sql("select status || ':' || coalesce(reason, '') from shipments where order_id = 42")
            .query(String::class.java).single()

    protected fun alertStatus(msgId: UUID) =
        jdbc.sql("select status || ':' || retry_count from alert_status where msg_id = ?").param(msgId)
            .query(String::class.java).single()
}

@SpringBootTest(properties = ["fault-injection.enabled=true"])
@AutoConfigureMockMvc
@Import(PostgresTestConfig::class)
class ShipmentAlertingTest @Autowired constructor(
    mvc: MockMvc,
    notifications: RecordingNotificationClient,
    jdbc: JdbcClient,
) : ShipmentTestBase(mvc, notifications, jdbc) {

    @Test
    fun `T-P1 biz fault records failure, reports one alert with observation ids`() {
        create("shipping:biz").andExpect { status { isUnprocessableContent() } }
        assertEquals("FAILED:ADDRESS_INVALID", shipmentStatus())
        val alert = notifications.sent.single()
        assertEquals("ADDRESS_INVALID", alert.errorCode)
        assertEquals("F1", alert.category)
        assertEquals("req-1", alert.observe.requestId)
        assertEquals("run-1", alert.observe.testRunId)
        assertEquals("SENT:0", alertStatus(alert.msgId))
    }

    @Test
    fun `fault targeted at another service is ignored`() {
        create("product:biz").andExpect { status { isCreated() } }
        assertEquals("CREATED:", shipmentStatus())
        assertEquals(0, notifications.sent.size)
    }

    @Test
    fun `T-P2 dup-send sends the same msgId twice but stores one status row`() {
        create("shipping:tech,shipping:dup-send").andExpect { status { isInternalServerError() } }
        assertEquals(2, notifications.sent.size)
        assertEquals(1, notifications.sent.map { it.msgId }.distinct().size)
        assertEquals(1, jdbc.sql("select count(*) from alert_status").query(Int::class.java).single())
    }

    @Test
    fun `T-P3 ack marks ACKED, unknown msgId is 404`() {
        create("shipping:tech")
        val msgId = notifications.sent.single().msgId
        mvc.post("/alerts/$msgId/ack").andExpect { status { isOk() } }
        assertEquals("ACKED:0", alertStatus(msgId))
        mvc.post("/alerts/${UUID.randomUUID()}/ack").andExpect { status { isNotFound() } }
    }

    @Test
    fun `T-P4 retry command resends the same payload and counts the retry`() {
        create("shipping:tech")
        val first = notifications.sent.single()
        mvc.post("/alerts/${first.msgId}/retry").andExpect { status { isAccepted() } }
        assertEquals(2, notifications.sent.size)
        assertEquals(first, notifications.sent[1])
        assertEquals("SENT:1", alertStatus(first.msgId))
        mvc.post("/alerts/${UUID.randomUUID()}/retry").andExpect { status { isNotFound() } }
    }
}

@SpringBootTest(properties = ["fault-injection.enabled=false"])
@AutoConfigureMockMvc
@Import(PostgresTestConfig::class)
class ShipmentFaultDisabledTest @Autowired constructor(
    mvc: MockMvc,
    notifications: RecordingNotificationClient,
    jdbc: JdbcClient,
) : ShipmentTestBase(mvc, notifications, jdbc) {
    @Test
    fun `T-P5 disabled injection ignores X-Fault (spec I2)`() {
        create("shipping:biz").andExpect { status { isCreated() } }
        assertEquals("CREATED:", shipmentStatus())
        assertEquals(0, notifications.sent.size)
    }
}
