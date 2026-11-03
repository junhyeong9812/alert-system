package dev.alertsystem.order.order

import dev.alertsystem.order.support.Headers
import dev.alertsystem.order.support.RequestContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper
import java.net.http.HttpClient
import java.time.Duration

sealed interface CallResult {
    data object Ok : CallResult
    data class Rejected(val code: String) : CallResult // 하위 서비스의 비즈니스 거절 (4xx)
    data class Failed(val reason: String) : CallResult // 5xx · 타임아웃 · 연결 실패
}

interface ProductClient {
    fun reserve(orderId: Long, productId: Long, quantity: Int): CallResult
    fun release(orderId: Long): CallResult
}

interface ShippingClient {
    fun create(orderId: Long, address: String): CallResult
}

// 요청 컨텍스트의 장애 주입·관측 헤더를 하위 호출에 그대로 전달한다.
val forwardContextHeaders = ClientHttpRequestInterceptor { request, body, execution ->
    val ctx = RequestContext.current()
    ctx.forwardFault?.let { request.headers.set(Headers.FAULT, it) }
    ctx.testRunId?.let { request.headers.set(Headers.TEST_RUN_ID, it) }
    ctx.requestId?.let { request.headers.set(Headers.REQUEST_ID, it) }
    execution.execute(request, body)
}

fun downstreamClient(baseUrl: String): RestClient = RestClient.builder()
    .baseUrl(baseUrl)
    .requestFactory(
        JdkClientHttpRequestFactory(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(1)).build())
            .apply { setReadTimeout(Duration.ofSeconds(2)) },
    )
    .requestInterceptor(forwardContextHeaders)
    .build()

private data class CodeBody(val code: String? = null)

private fun RestClient.RequestBodySpec.call(mapper: ObjectMapper): CallResult = try {
    exchange { _, res ->
        val status = res.statusCode.value()
        when {
            status in 200..299 -> CallResult.Ok
            status in 400..499 -> CallResult.Rejected(
                runCatching { mapper.readValue(res.body, CodeBody::class.java).code }.getOrNull() ?: "HTTP_$status",
            )
            else -> CallResult.Failed("HTTP_$status")
        }
    }
} catch (e: Exception) {
    // 근본 원인 클래스를 남긴다: ConnectException(도달 못 함) vs HttpTimeoutException·IOException(결과 불명)
    CallResult.Failed(generateSequence<Throwable>(e) { it.cause }.last().javaClass.simpleName)
}

@Component
class HttpProductClient(
    @Value("\${downstream.product-url}") url: String,
    private val mapper: ObjectMapper,
) : ProductClient {
    private val client = downstreamClient(url)

    override fun reserve(orderId: Long, productId: Long, quantity: Int) =
        client.post().uri("/stock/reserve")
            .body(mapOf("orderId" to orderId, "productId" to productId, "quantity" to quantity))
            .call(mapper)

    override fun release(orderId: Long) =
        client.post().uri("/stock/release").body(mapOf("orderId" to orderId)).call(mapper)
}

@Component
class HttpShippingClient(
    @Value("\${downstream.shipping-url}") url: String,
    private val mapper: ObjectMapper,
) : ShippingClient {
    private val client = downstreamClient(url)

    override fun create(orderId: Long, address: String) =
        client.post().uri("/shipments").body(mapOf("orderId" to orderId, "address" to address)).call(mapper)
}
