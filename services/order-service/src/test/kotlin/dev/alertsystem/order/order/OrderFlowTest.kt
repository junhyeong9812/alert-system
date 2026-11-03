package dev.alertsystem.order.order

import dev.alertsystem.order.PostgresTestConfig
import dev.alertsystem.order.RecordingNotificationClient
import dev.alertsystem.order.support.RequestContext
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import kotlin.test.Test
import kotlin.test.assertEquals

class FakeProduct : ProductClient {
    var reserveResult: CallResult = CallResult.Ok
    var releaseResult: CallResult = CallResult.Ok
    val released = mutableListOf<Long>()
    override fun reserve(orderId: Long, productId: Long, quantity: Int) = reserveResult
    override fun release(orderId: Long): CallResult = releaseResult.also { released.add(orderId) }
}

class FakeShipping : ShippingClient {
    var result: CallResult = CallResult.Ok
    var calls = 0
    override fun create(orderId: Long, address: String) = result.also { calls++ }
}

@TestConfiguration(proxyBeanMethods = false)
class FakeDownstreamConfig {
    @Bean @Primary fun fakeProduct() = FakeProduct()
    @Bean @Primary fun fakeShipping() = FakeShipping()
}

@SpringBootTest(properties = ["fault-injection.enabled=true"])
@Import(PostgresTestConfig::class, FakeDownstreamConfig::class)
class OrderFlowTest @Autowired constructor(
    private val service: OrderService,
    private val product: FakeProduct,
    private val shipping: FakeShipping,
    private val notifications: RecordingNotificationClient,
    private val jdbc: JdbcClient,
) {
    private val cmd = PlaceOrder(productId = 1, quantity = 1, address = "Seoul")

    @BeforeEach
    fun reset() {
        product.reserveResult = CallResult.Ok
        product.releaseResult = CallResult.Ok
        product.released.clear()
        shipping.result = CallResult.Ok
        shipping.calls = 0
        notifications.sent.clear()
        jdbc.sql("truncate orders, alert_status").update()
    }

    private fun place(fault: String? = null) =
        RequestContext.with(RequestContext.of(true, "order", fault, "run-1", "req-1")) { service.place(cmd) }

    private fun orderStatus(id: Long) =
        jdbc.sql("select status from orders where id = ?").param(id).query(String::class.java).single()

    @Test
    fun `T-O1 happy path confirms the order`() {
        val (status, result) = place()
        assertEquals(HttpStatus.CREATED, status)
        assertEquals("CONFIRMED", orderStatus(result.orderId))
        assertEquals(0, notifications.sent.size)
    }

    @Test
    fun `T-O2 product rejection fails without calling shipping`() {
        product.reserveResult = CallResult.Rejected("OUT_OF_STOCK")
        val (status, result) = place()
        assertEquals(HttpStatus.CONFLICT, status)
        assertEquals("FAILED", orderStatus(result.orderId))
        assertEquals(0, shipping.calls)
        assertEquals(0, notifications.sent.size) // SG-01: 하위 실패는 하위 서비스가 직접 알린다
    }

    @Test
    fun `T-O3 shipping failure triggers compensation`() {
        shipping.result = CallResult.Rejected("ADDRESS_INVALID")
        val (status, result) = place()
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, status)
        assertEquals("FAILED", orderStatus(result.orderId))
        assertEquals(listOf(result.orderId), product.released)
    }

    @Test
    fun `T-O4 compensation failure leaves COMPENSATION_FAILED`() {
        shipping.result = CallResult.Failed("HTTP_500")
        product.releaseResult = CallResult.Failed("HTTP_500")
        val (status, result) = place()
        assertEquals(HttpStatus.BAD_GATEWAY, status)
        assertEquals("COMPENSATION_FAILED", orderStatus(result.orderId))
    }

    @Test
    fun `own biz fault records failure then reports one alert with observation ids`() {
        val (status, result) = place("order:biz")
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, status)
        assertEquals("FAILED", orderStatus(result.orderId))
        val alert = notifications.sent.single()
        assertEquals("ORDER_LIMIT_EXCEEDED", alert.errorCode)
        assertEquals("req-1", alert.observe.requestId)
        assertEquals(
            "SENT",
            jdbc.sql("select status from alert_status where msg_id = ?").param(alert.msgId)
                .query(String::class.java).single(),
        )
    }
}
