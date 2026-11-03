package dev.alertsystem.product

import dev.alertsystem.product.alerting.AlertPayload
import dev.alertsystem.product.alerting.NotificationClient
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.concurrent.CopyOnWriteArrayList

@TestConfiguration(proxyBeanMethods = false)
class PostgresTestConfig {
    @Bean
    @ServiceConnection
    fun postgres() = PostgreSQLContainer("postgres:17")

    @Bean
    @Primary
    fun recordingNotificationClient() = RecordingNotificationClient()
}

class RecordingNotificationClient : NotificationClient {
    val sent = CopyOnWriteArrayList<AlertPayload>()
    override fun send(payload: AlertPayload): Boolean = sent.add(payload)
}
