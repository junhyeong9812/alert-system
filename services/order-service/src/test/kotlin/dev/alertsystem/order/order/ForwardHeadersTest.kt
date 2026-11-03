package dev.alertsystem.order.order

import dev.alertsystem.order.support.RequestContext
import org.springframework.http.HttpMethod
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import kotlin.test.Test

class ForwardHeadersTest {
    @Test
    fun `T-O5 forwards fault and observation headers downstream`() {
        val builder = RestClient.builder().requestInterceptor(forwardContextHeaders)
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("http://product/stock/reserve"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("X-Fault", "product:biz"))
            .andExpect(header("X-Test-Run-Id", "run-1"))
            .andExpect(header("X-Request-Id", "req-1"))
            .andRespond(withSuccess())

        val client = builder.build()
        RequestContext.with(RequestContext.of(true, "order", "product:biz", "run-1", "req-1")) {
            client.post().uri("http://product/stock/reserve").retrieve().toBodilessEntity()
        }
        server.verify()
    }
}
