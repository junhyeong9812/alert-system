package dev.alertsystem.notification.dispatch

import dev.alertsystem.notification.inbox.AlertPayload
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper
import java.net.http.HttpClient
import java.time.Duration

// 채널 추상화 (docs/01-problem/03-ownership-and-routing.md §5)
interface Notifier {
    val name: String
    fun send(alert: AlertPayload): Boolean
}

interface ChannelRouter {
    fun channelsFor(alert: AlertPayload): List<Notifier>
}

// critical → Slack + Email, 그 외 → Slack
@Component
class SeverityRouter(
    @Qualifier("slackNotifier") private val slack: Notifier,
    @Qualifier("emailNotifier") private val email: Notifier,
) : ChannelRouter {
    override fun channelsFor(alert: AlertPayload) =
        if (alert.severity == "critical") listOf(slack, email) else listOf(slack)
}

@Component("slackNotifier")
class SlackNotifier(@Value("\${alert.slack-webhook-url}") webhookUrl: String) : Notifier {
    private val log = LoggerFactory.getLogger(javaClass)
    override val name = "slack"
    private val client = RestClient.builder()
        .baseUrl(webhookUrl)
        .requestFactory(
            JdkClientHttpRequestFactory(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(1)).build())
                .apply { setReadTimeout(Duration.ofSeconds(5)) },
        )
        .build()

    // Slack Incoming Webhook 형식 + message metadata (실제 Slack도 metadata.event_payload를 받는다).
    // event_payload의 관측 id는 sink 측 정답 대조 전용이다.
    override fun send(alert: AlertPayload): Boolean = try {
        client.post().body(
            mapOf(
                "text" to "[${alert.severity.uppercase()}] ${alert.service}-service ${alert.errorCode}: ${alert.message}",
                "metadata" to mapOf(
                    "event_type" to "alert",
                    "event_payload" to mapOf(
                        "msgId" to alert.msgId.toString(),
                        "service" to alert.service,
                        "category" to alert.category,
                        "errorCode" to alert.errorCode,
                        "severity" to alert.severity,
                        "occurredAt" to alert.occurredAt.toString(),
                        "testRunId" to alert.observe.testRunId,
                        "requestId" to alert.observe.requestId,
                    ),
                ),
            ),
        ).retrieve().toBodilessEntity()
        true
    } catch (e: Exception) {
        log.warn("slack send failed msgId={} cause={}", alert.msgId, e.message)
        false
    }
}

@Component("emailNotifier")
class EmailNotifier(
    private val mail: JavaMailSender,
    private val mapper: ObjectMapper,
    @Value("\${alert.email.from}") private val sender: String,
    @Value("\${alert.email.to}") private val recipient: String,
) : Notifier {
    private val log = LoggerFactory.getLogger(javaClass)
    override val name = "email"

    override fun send(alert: AlertPayload): Boolean = try {
        mail.send(
            SimpleMailMessage().apply {
                setFrom(sender)
                setTo(recipient)
                subject = "[${alert.severity.uppercase()}] ${alert.service}-service ${alert.errorCode} (${alert.msgId})"
                text = mapper.writeValueAsString(alert)
            },
        )
        true
    } catch (e: Exception) {
        log.warn("email send failed msgId={} cause={}", alert.msgId, e.message)
        false
    }
}
