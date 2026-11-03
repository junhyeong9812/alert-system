package dev.alertsystem.notification

import dev.alertsystem.notification.dispatch.CallbackClient
import dev.alertsystem.notification.dispatch.ChannelRouter
import dev.alertsystem.notification.dispatch.Dispatcher
import dev.alertsystem.notification.dispatch.Notifier
import dev.alertsystem.notification.dispatch.RetryBatch
import dev.alertsystem.notification.dispatch.SeverityRouter
import dev.alertsystem.notification.inbox.AlertPayload
import dev.alertsystem.notification.inbox.InboxRepository
import dev.alertsystem.notification.inbox.Observe
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.simple.JdbcClient
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

class FakeNotifier(override val name: String) : Notifier {
    @Volatile var succeed = true
    val sent = CopyOnWriteArrayList<UUID>()
    override fun send(alert: AlertPayload): Boolean = succeed.also { if (it) sent.add(alert.msgId) }
}

class FakeCallbacks : CallbackClient {
    val acks = CopyOnWriteArrayList<Pair<String, UUID>>()
    val retries = CopyOnWriteArrayList<Pair<String, UUID>>()
    @Volatile var retrySucceeds = true
    var onRetry: (UUID) -> Unit = {}
    override fun ack(callbackUrl: String, msgId: UUID) = acks.add(callbackUrl to msgId)
    override fun retry(callbackUrl: String, msgId: UUID): Boolean {
        retries.add(callbackUrl to msgId)
        if (retrySucceeds) onRetry(msgId)
        return retrySucceeds
    }
}

@TestConfiguration(proxyBeanMethods = false)
class NotificationTestConfig {
    @Bean @ServiceConnection fun postgres() = PostgreSQLContainer("postgres:17")
    @Bean fun fakeSlack() = FakeNotifier("slack")
    @Bean fun fakeEmail() = FakeNotifier("email")
    @Bean @Primary fun fakeRouter(fakeSlack: FakeNotifier, fakeEmail: FakeNotifier): ChannelRouter =
        SeverityRouter(fakeSlack, fakeEmail)
    @Bean @Primary fun fakeCallbacks() = FakeCallbacks()
}

@SpringBootTest(properties = ["alert.scheduling.enabled=false", "alert.retry-batch.threshold=3"])
@Import(NotificationTestConfig::class)
class Sg01NotificationTest @Autowired constructor(
    private val inbox: InboxRepository,
    private val dispatcher: Dispatcher,
    private val retryBatch: RetryBatch,
    private val fakeSlack: FakeNotifier,
    private val fakeEmail: FakeNotifier,
    private val callbacks: FakeCallbacks,
    private val jdbc: JdbcClient,
) {
    @BeforeEach
    fun reset() {
        jdbc.sql("truncate inbox").update()
        fakeSlack.succeed = true
        fakeSlack.sent.clear()
        fakeEmail.succeed = true
        fakeEmail.sent.clear()
        callbacks.acks.clear()
        callbacks.retries.clear()
        callbacks.retrySucceeds = true
        callbacks.onRetry = {}
    }

    private fun alert(severity: String = "warn") = AlertPayload(
        UUID.randomUUID(), "shipping", "F2", "TECH_ERROR", severity, "boom", Instant.now(),
        "http://shipping:8083", Observe("run-1", "req-1"),
    )

    private fun row(msgId: UUID) =
        jdbc.sql("select status || ':' || fail_count || ':' || received_count || ':' || retry_command_count from inbox where msg_id = ?")
            .param(msgId).query(String::class.java).single()

    @Test
    fun `T-N1 duplicate msgId is stored once and sent once`() {
        val a = alert()
        inbox.receive(a)
        inbox.receive(a)
        dispatcher.runOnce()
        dispatcher.runOnce()
        assertEquals("SENT:0:2:0", row(a.msgId))
        assertEquals(listOf(a.msgId), fakeSlack.sent)
    }

    @Test
    fun `T-N2 channel failure marks FAILED and does not ack`() {
        fakeSlack.succeed = false
        val a = alert()
        inbox.receive(a)
        dispatcher.runOnce()
        assertEquals("FAILED:1:1:0", row(a.msgId))
        assertEquals(0, callbacks.acks.size)
    }

    @Test
    fun `T-N3 successful send marks SENT and acks the producer callback`() {
        val a = alert()
        inbox.receive(a)
        dispatcher.runOnce()
        assertEquals("SENT:0:1:0", row(a.msgId))
        assertEquals(listOf("http://shipping:8083" to a.msgId), callbacks.acks.toList())
    }

    @Test
    fun `T-N4 retry batch commands nothing below threshold, every failed msg at threshold`() {
        fakeSlack.succeed = false
        val alerts = List(3) { alert() }
        inbox.receive(alerts[0]); inbox.receive(alerts[1])
        dispatcher.runOnce()
        assertEquals(0, retryBatch.runOnce())
        assertEquals(0, callbacks.retries.size)

        inbox.receive(alerts[2])
        dispatcher.runOnce()
        assertEquals(3, retryBatch.runOnce())
        assertEquals(alerts.map { it.msgId }.toSet(), callbacks.retries.map { it.second }.toSet())
        alerts.forEach { assertEquals("RETRY_REQUESTED:1:1:1", row(it.msgId)) }
    }

    @Test
    fun `failed retry command reverts to FAILED`() {
        fakeSlack.succeed = false
        val alerts = List(3) { alert() }
        alerts.forEach(inbox::receive)
        dispatcher.runOnce()
        callbacks.retrySucceeds = false
        retryBatch.runOnce()
        alerts.forEach { assertEquals("FAILED:1:1:1", row(it.msgId)) }
    }

    @Test
    fun `T-N5 producer resend after a retry command is dispatched again`() {
        fakeSlack.succeed = false
        val alerts = List(3) { alert() }
        alerts.forEach(inbox::receive)
        dispatcher.runOnce()
        // 생산자가 재시도 명령을 받으면 같은 payload를 재전송한다
        callbacks.onRetry = { id -> inbox.receive(alerts.first { it.msgId == id }) }
        retryBatch.runOnce()
        alerts.forEach { assertEquals("RECEIVED:1:2:1", row(it.msgId)) }

        fakeSlack.succeed = true
        dispatcher.runOnce()
        alerts.forEach { assertEquals("SENT:1:2:1", row(it.msgId)) }
    }

    @Test
    fun `T-N6 stale SENDING rows go back to RECEIVED`() {
        val a = alert()
        inbox.receive(a)
        jdbc.sql("update inbox set status = 'SENDING', updated_at = now() - interval '2 minutes'").update()
        assertEquals(1, inbox.recoverStale(60))
        assertEquals("RECEIVED:0:1:0", row(a.msgId))
    }

    @Test
    fun `T-N7 critical alerts go to slack and email`() {
        val warn = alert("warn")
        val critical = alert("critical")
        inbox.receive(warn)
        inbox.receive(critical)
        dispatcher.runOnce()
        assertEquals(setOf(warn.msgId, critical.msgId), fakeSlack.sent.toSet())
        assertEquals(listOf(critical.msgId), fakeEmail.sent)
    }
}
