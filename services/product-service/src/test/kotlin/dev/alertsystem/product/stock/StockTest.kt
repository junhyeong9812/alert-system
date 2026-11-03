package dev.alertsystem.product.stock

import dev.alertsystem.product.PostgresTestConfig
import dev.alertsystem.product.RecordingNotificationClient
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import kotlin.test.Test
import kotlin.test.assertEquals

@SpringBootTest(properties = ["fault-injection.enabled=true"])
@AutoConfigureMockMvc
@Import(PostgresTestConfig::class)
class StockTest @Autowired constructor(
    private val mvc: MockMvc,
    private val notifications: RecordingNotificationClient,
    private val jdbc: JdbcClient,
) {
    @BeforeEach
    fun reset() {
        notifications.sent.clear()
        jdbc.sql("truncate reservations, alert_status").update()
        jdbc.sql("update products set stock = 100 where id = 1").update()
    }

    private fun stock() = jdbc.sql("select stock from products where id = 1").query(Long::class.java).single()

    private fun post(path: String, body: String, fault: String? = null) = mvc.post(path) {
        contentType = MediaType.APPLICATION_JSON
        content = body
        fault?.let { header("X-Fault", it) }
    }

    @Test
    fun `T-R1 reserve decrements stock`() {
        post("/stock/reserve", """{"orderId":1,"productId":1,"quantity":3}""").andExpect { status { isOk() } }
        assertEquals(97, stock())
    }

    @Test
    fun `real out-of-stock is a biz failure with an alert`() {
        post("/stock/reserve", """{"orderId":1,"productId":1,"quantity":1000}""").andExpect { status { isConflict() } }
        assertEquals(100, stock())
        assertEquals("OUT_OF_STOCK", notifications.sent.single().errorCode)
    }

    @Test
    fun `T-R2 compensation-fail returns 500 and reports a critical F5 alert`() {
        post("/stock/reserve", """{"orderId":1,"productId":1,"quantity":3}""")
        post("/stock/release", """{"orderId":1}""", "product:compensation-fail")
            .andExpect { status { isInternalServerError() } }
        assertEquals(97, stock())
        val alert = notifications.sent.single()
        assertEquals("COMPENSATION_FAILED", alert.errorCode)
        assertEquals("F5", alert.category)
        assertEquals("critical", alert.severity)
    }

    @Test
    fun `T-R3 release restores stock once (idempotent)`() {
        post("/stock/reserve", """{"orderId":1,"productId":1,"quantity":3}""")
        post("/stock/release", """{"orderId":1}""").andExpect { status { isOk() } }
        post("/stock/release", """{"orderId":1}""").andExpect { status { isOk() } }
        assertEquals(100, stock())
    }
}
