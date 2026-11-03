package dev.alertsystem.order.alerting

import dev.alertsystem.order.support.RequestContext
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import tools.jackson.databind.ObjectMapper
import java.net.http.HttpClient
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

// SG-01 (최초 설계, 기준선) 생산자 측: msgId 발급 → alert_status 기록 → 알림 서비스 전송 → ack 수신 / 재시도 명령 수신.
// 기준선이므로 문서의 결함(L1 ack 유실, L4 재시도 증폭, L6 dual write)을 고치지 않는다 (spec I4).
// 이 파일은 order/product/shipping에 패키지명만 바꿔 복사되어 있다.

data class Observe(val testRunId: String?, val requestId: String?)

data class AlertPayload(
    val msgId: UUID,
    val service: String,
    val category: String,
    val errorCode: String,
    val severity: String,
    val message: String,
    val occurredAt: Instant,
    val callbackUrl: String,
    val observe: Observe,
)

interface NotificationClient {
    fun send(payload: AlertPayload): Boolean
}

@Component
class HttpNotificationClient(
    @Value("\${alert.notification-url}") notificationUrl: String,
    private val registry: MeterRegistry,
) : NotificationClient {
    private val log = LoggerFactory.getLogger(javaClass)
    private val client = RestClient.builder()
        .baseUrl(notificationUrl)
        .requestFactory(
            JdkClientHttpRequestFactory(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(1)).build())
                .apply { setReadTimeout(Duration.ofSeconds(2)) },
        )
        .build()

    override fun send(payload: AlertPayload): Boolean = try {
        client.post().uri("/alerts").body(payload).retrieve().toBodilessEntity()
        registry.counter("alert_send_total", "result", "ok").increment()
        true
    } catch (e: Exception) {
        // 원안: 전송 실패 시 상태는 SENT로 남고 생산자는 재전송하지 않는다 (재시도는 알림 서비스의 명령으로만).
        registry.counter("alert_send_total", "result", "error").increment()
        log.warn("alert send failed msgId={} cause={}", payload.msgId, e.message)
        false
    }
}

@Component
class AlertReporter(
    private val jdbc: JdbcClient,
    private val mapper: ObjectMapper,
    private val client: NotificationClient,
    @Value("\${app.service-name}") private val serviceName: String,
    @Value("\${alert.callback-base-url}") private val callbackBaseUrl: String,
) {
    // 호출 시점: 비즈니스 실패 기록이 커밋된 뒤 (커밋과 전송 사이가 dual write 공백).
    fun report(category: String, errorCode: String, severity: String, message: String) {
        val ctx = RequestContext.current()
        val payload = AlertPayload(
            msgId = UUID.randomUUID(),
            service = serviceName,
            category = category,
            errorCode = errorCode,
            severity = severity,
            message = message,
            occurredAt = Instant.now(),
            callbackUrl = callbackBaseUrl,
            observe = Observe(ctx.testRunId, ctx.requestId),
        )
        jdbc.sql(
            """
            insert into alert_status(msg_id, payload, status, retry_count, created_at, updated_at)
            values (?, ?::jsonb, 'SENT', 0, now(), now())
            """.trimIndent(),
        ).params(payload.msgId, mapper.writeValueAsString(payload)).update()
        client.send(payload)
        if (ctx.has("dup-send")) client.send(payload) // FI-14: 같은 msgId 이중 전송
    }

    fun ack(msgId: UUID): Boolean =
        jdbc.sql("update alert_status set status = 'ACKED', updated_at = now() where msg_id = ?")
            .param(msgId).update() > 0

    // 알림 서비스의 "재시도해라" 명령 → 보관한 payload를 같은 msgId로 재전송.
    fun retry(msgId: UUID): Boolean {
        val raw = jdbc.sql("select payload::text from alert_status where msg_id = ?")
            .param(msgId).query(String::class.java).optional().orElse(null) ?: return false
        jdbc.sql(
            "update alert_status set status = 'SENT', retry_count = retry_count + 1, updated_at = now() where msg_id = ?",
        ).param(msgId).update()
        client.send(mapper.readValue(raw, AlertPayload::class.java))
        return true
    }
}

@RestController
class AlertCallbackController(private val reporter: AlertReporter) {
    @PostMapping("/alerts/{msgId}/ack")
    fun ack(@PathVariable msgId: UUID): ResponseEntity<Void> =
        if (reporter.ack(msgId)) ResponseEntity.ok().build() else ResponseEntity.notFound().build()

    @PostMapping("/alerts/{msgId}/retry")
    fun retry(@PathVariable msgId: UUID): ResponseEntity<Void> =
        if (reporter.retry(msgId)) ResponseEntity.accepted().build() else ResponseEntity.notFound().build()
}

@Component
class AlertStatusMetrics(private val jdbc: JdbcClient, registry: MeterRegistry) {
    private val counts = listOf("SENT", "ACKED").associateWith { AtomicLong() }

    init {
        counts.forEach { (status, value) ->
            Gauge.builder("alert_status_count", value) { it.get().toDouble() }.tag("status", status).register(registry)
        }
    }

    @Scheduled(fixedDelay = 5000)
    fun refresh() {
        val rows = jdbc.sql("select status, count(*) from alert_status group by status")
            .query { rs, _ -> rs.getString(1) to rs.getLong(2) }.list().toMap()
        counts.forEach { (status, value) -> value.set(rows[status] ?: 0) }
    }
}
