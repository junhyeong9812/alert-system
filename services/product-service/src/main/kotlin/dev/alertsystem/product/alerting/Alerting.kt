package dev.alertsystem.product.alerting

import dev.alertsystem.product.support.RequestContext
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import tools.jackson.databind.ObjectMapper
import java.net.http.HttpClient
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

// SG-01 (최초 설계, 기준선) 생산자 측: msgId 발급 → alert_status 기록 → 알림 서비스 전송 → ack 수신(미수신 시 재발송) / 재시도 명령 수신.
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
        // 전송 실패 시 상태는 SENT로 남는다 → ack가 오지 않으므로 ack 미수신 재발송(resendUnacked)의 대상이 된다.
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
    @Value("\${alert.ack-resend.timeout-seconds}") private val ackTimeoutSeconds: Long,
    @Value("\${alert.ack-resend.max}") private val maxResends: Int,
    private val registry: MeterRegistry,
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

    // 원안 L1: ack가 ackTimeout 안에 오지 않으면 같은 msgId로 재발송한다 (최대 maxResends회, 재발송마다 타이머 재시작).
    // ack 유실이면 알림은 이미 발송됐으므로 재발송은 inbox dedup에 걸리거나(정상) 중복 발송으로 이어진다(L1·L2).
    fun resendUnacked(): Int {
        val due = jdbc.sql(
            """
            update alert_status set ack_resend_count = ack_resend_count + 1, updated_at = now()
            where msg_id in (
                select msg_id from alert_status
                where status = 'SENT' and ack_resend_count < ? and updated_at < now() - make_interval(secs => ?)
                order by updated_at limit 200 for update skip locked
            )
            returning payload::text
            """.trimIndent(),
        ).param(maxResends).param(ackTimeoutSeconds.toDouble()).query(String::class.java).list()
        due.forEach { client.send(mapper.readValue(it, AlertPayload::class.java)) }
        registry.counter("alert_ack_resend_total").increment(due.size.toDouble())
        return due.size
    }

    // 알림 서비스의 발송 결과 회신: ACKED(발송 성공) | FAILED(발송 실패 — 이후 재시도 명령을 기다린다)
    fun ack(msgId: UUID, status: String): Boolean =
        jdbc.sql("update alert_status set status = ?, updated_at = now() where msg_id = ?")
            .param(status).param(msgId).update() > 0

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

data class AckBody(val status: String = "ACKED")

@RestController
class AlertCallbackController(private val reporter: AlertReporter) {
    @PostMapping("/alerts/{msgId}/ack")
    fun ack(@PathVariable msgId: UUID, @RequestBody(required = false) body: AckBody?): ResponseEntity<Void> {
        val status = body?.status ?: "ACKED"
        if (status !in setOf("ACKED", "FAILED")) return ResponseEntity.badRequest().build()
        return if (reporter.ack(msgId, status)) ResponseEntity.ok().build() else ResponseEntity.notFound().build()
    }

    @PostMapping("/alerts/{msgId}/retry")
    fun retry(@PathVariable msgId: UUID): ResponseEntity<Void> =
        if (reporter.retry(msgId)) ResponseEntity.accepted().build() else ResponseEntity.notFound().build()
}

@Component
class AlertStatusMetrics(private val jdbc: JdbcClient, registry: MeterRegistry) {
    private val counts = listOf("SENT", "ACKED", "FAILED").associateWith { AtomicLong() }

    init {
        counts.forEach { (status, value) ->
            Gauge.builder("alert_status_count", value) { it.get().toDouble() }.tag("status", status).register(registry)
        }
    }

    fun refresh() {
        val rows = jdbc.sql("select status, count(*) from alert_status group by status")
            .query { rs, _ -> rs.getString(1) to rs.getLong(2) }.list().toMap()
        counts.forEach { (status, value) -> value.set(rows[status] ?: 0) }
    }
}

// 스케줄 트리거만 분리 — 테스트는 alert.scheduling.enabled=false로 끄고 메서드를 직접 호출한다.
@Component
@ConditionalOnProperty("alert.scheduling.enabled", havingValue = "true", matchIfMissing = true)
class AlertSchedules(private val reporter: AlertReporter, private val metrics: AlertStatusMetrics) {
    @Scheduled(fixedDelay = 5000)
    fun resendUnacked() {
        reporter.resendUnacked()
    }

    @Scheduled(fixedDelay = 5000)
    fun metrics() {
        metrics.refresh()
    }
}
