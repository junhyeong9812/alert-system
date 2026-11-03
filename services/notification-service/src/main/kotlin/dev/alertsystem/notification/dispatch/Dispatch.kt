package dev.alertsystem.notification.dispatch

import dev.alertsystem.notification.inbox.InboxRepository
import dev.alertsystem.notification.inbox.InboxRow
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

// 생산자 콜백: 발송 결과 ack {status: ACKED | FAILED} (원안 ② "ack {msgId, status}"), 재시도 명령
interface CallbackClient {
    fun ack(callbackUrl: String, msgId: UUID, status: String): Boolean
    fun retry(callbackUrl: String, msgId: UUID): Boolean
}

@Component
class HttpCallbackClient : CallbackClient {
    private val log = LoggerFactory.getLogger(javaClass)
    private val client = RestClient.builder()
        .requestFactory(
            JdkClientHttpRequestFactory(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(1)).build())
                .apply { setReadTimeout(Duration.ofSeconds(2)) },
        )
        .build()

    override fun ack(callbackUrl: String, msgId: UUID, status: String) =
        call("$callbackUrl/alerts/$msgId/ack", mapOf("status" to status))
    override fun retry(callbackUrl: String, msgId: UUID) = call("$callbackUrl/alerts/$msgId/retry", null)

    private fun call(url: String, body: Any?): Boolean = try {
        client.post().uri(url).apply { if (body != null) body(body) }.retrieve().toBodilessEntity()
        true
    } catch (e: Exception) {
        log.warn("callback failed url={} cause={}", url, e.message)
        false
    }
}

private val virtualThreads = Executors.newVirtualThreadPerTaskExecutor()

private fun <T> List<T>.forEachParallel(block: (T) -> Unit) =
    map { virtualThreads.submit { block(it) } }.forEach { it.get() }

@Component
class Dispatcher(
    private val inbox: InboxRepository,
    private val router: ChannelRouter,
    private val callbacks: CallbackClient,
    private val registry: MeterRegistry,
    @Value("\${alert.dispatch.batch-size}") private val batchSize: Int,
) {
    // 원안: 발송 1회 시도. 성공 → SENT + ack, 실패 → FAILED (내부 재시도 없음 — 재시도는 배치의 명령으로만).
    fun runOnce(): Int {
        val rows = inbox.claim(batchSize)
        rows.forEachParallel(::dispatch)
        return rows.size
    }

    private fun dispatch(row: InboxRow) {
        // 모든 채널을 각각 시도한다 (critical의 Slack 실패가 Email 시도를 막지 않도록). 전부 성공해야 SENT.
        val results = router.channelsFor(row.payload).map { channel ->
            channel.send(row.payload).also {
                registry.counter("notifier_send_total", "channel", channel.name, "result", if (it) "ok" else "error").increment()
            }
        }
        val delivered = results.all { it }
        if (delivered) inbox.markSent(row.msgId) else inbox.markFailed(row.msgId)
        val status = if (delivered) "ACKED" else "FAILED"
        val acked = callbacks.ack(row.callbackUrl, row.msgId, status)
        registry.counter("ack_total", "status", status, "result", if (acked) "ok" else "error").increment()
    }
}

@Component
class RetryBatch(
    private val inbox: InboxRepository,
    private val callbacks: CallbackClient,
    private val registry: MeterRegistry,
    @Value("\${alert.retry-batch.threshold}") private val threshold: Int,
) {
    private val lastFailed = AtomicLong()

    init {
        Gauge.builder("retry_batch_failed_count", lastFailed) { it.get().toDouble() }.register(registry)
    }

    // 원안: 실패 건수를 세어 임계치를 초과하면 실패한 msgId마다 생산자에게 "재시도해라" 명령.
    fun runOnce(): Int {
        val failed = inbox.failed()
        lastFailed.set(failed.size.toLong())
        if (failed.size <= threshold) return 0
        failed.forEachParallel { (msgId, callbackUrl) ->
            if (!inbox.markRetryRequested(msgId)) return@forEachParallel
            val ok = callbacks.retry(callbackUrl, msgId)
            registry.counter("retry_commands_total", "result", if (ok) "ok" else "error").increment()
            if (!ok) inbox.revertRetry(msgId)
        }
        return failed.size
    }
}

@Component
class InboxMetrics(private val inbox: InboxRepository, registry: MeterRegistry) {
    private val counts = listOf("RECEIVED", "SENDING", "SENT", "FAILED", "RETRY_REQUESTED").associateWith { AtomicLong() }

    init {
        counts.forEach { (status, value) ->
            Gauge.builder("inbox_count", value) { it.get().toDouble() }.tag("status", status).register(registry)
        }
    }

    fun refresh() {
        val rows = inbox.countsByStatus()
        counts.forEach { (status, value) -> value.set(rows[status] ?: 0) }
    }
}

// 스케줄 트리거만 분리 — 테스트는 alert.scheduling.enabled=false로 끄고 runOnce를 직접 호출한다.
@Component
@ConditionalOnProperty("alert.scheduling.enabled", havingValue = "true", matchIfMissing = true)
class Schedules(
    private val dispatcher: Dispatcher,
    private val retryBatch: RetryBatch,
    private val inbox: InboxRepository,
    private val metrics: InboxMetrics,
    @Value("\${alert.dispatch.stale-seconds}") private val staleSeconds: Long,
) {
    @Scheduled(fixedDelayString = "\${alert.dispatch.poll-ms}")
    fun dispatch() {
        dispatcher.runOnce()
    }

    @Scheduled(fixedDelayString = "\${alert.retry-batch.interval-ms}", initialDelayString = "\${alert.retry-batch.interval-ms}")
    fun retryBatch() {
        retryBatch.runOnce()
    }

    @Scheduled(fixedDelay = 10_000)
    fun recoverStale() {
        inbox.recoverStale(staleSeconds)
    }

    @Scheduled(fixedDelay = 5_000)
    fun metrics() {
        metrics.refresh()
    }
}
