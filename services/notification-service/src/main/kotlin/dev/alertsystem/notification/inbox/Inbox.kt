package dev.alertsystem.notification.inbox

import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

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

data class InboxRow(val msgId: UUID, val payload: AlertPayload, val callbackUrl: String)

// SG-01 inbox 상태: RECEIVED → SENDING → SENT | FAILED → (재시도 명령) RETRY_REQUESTED → (생산자 재전송) RECEIVED
@Repository
class InboxRepository(private val jdbc: JdbcClient, private val mapper: ObjectMapper) {

    // 같은 msgId 재수신은 무시(dedup)한다. 단 재시도 명령을 보낸 메시지의 재전송은 다시 발송 대상으로 돌린다.
    fun receive(p: AlertPayload) {
        jdbc.sql(
            """
            insert into inbox(msg_id, service, payload, callback_url, status, fail_count, sent_count,
                              received_count, retry_command_count, created_at, updated_at)
            values (?, ?, ?::jsonb, ?, 'RECEIVED', 0, 0, 1, 0, now(), now())
            on conflict (msg_id) do update set
                received_count = inbox.received_count + 1,
                status = case when inbox.status = 'RETRY_REQUESTED' then 'RECEIVED' else inbox.status end,
                updated_at = now()
            """.trimIndent(),
        ).params(p.msgId, p.service, mapper.writeValueAsString(p), p.callbackUrl).update()
    }

    fun claim(limit: Int): List<InboxRow> =
        jdbc.sql(
            """
            update inbox set status = 'SENDING', updated_at = now()
            where msg_id in (
                select msg_id from inbox where status = 'RECEIVED'
                order by created_at limit ? for update skip locked
            )
            returning msg_id, payload::text, callback_url
            """.trimIndent(),
        ).param(limit).query { rs, _ ->
            InboxRow(
                rs.getObject(1, UUID::class.java),
                mapper.readValue(rs.getString(2), AlertPayload::class.java),
                rs.getString(3),
            )
        }.list()

    fun markSent(msgId: UUID) {
        jdbc.sql("update inbox set status = 'SENT', sent_count = sent_count + 1, updated_at = now() where msg_id = ? and status = 'SENDING'")
            .param(msgId).update()
    }

    fun markFailed(msgId: UUID) {
        jdbc.sql("update inbox set status = 'FAILED', fail_count = fail_count + 1, updated_at = now() where msg_id = ? and status = 'SENDING'")
            .param(msgId).update()
    }

    fun failed(): List<Pair<UUID, String>> =
        jdbc.sql("select msg_id, callback_url from inbox where status = 'FAILED' order by updated_at")
            .query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getString(2) }.list()

    fun markRetryRequested(msgId: UUID): Boolean =
        jdbc.sql(
            "update inbox set status = 'RETRY_REQUESTED', retry_command_count = retry_command_count + 1, updated_at = now() where msg_id = ? and status = 'FAILED'",
        ).param(msgId).update() > 0

    fun revertRetry(msgId: UUID) {
        jdbc.sql("update inbox set status = 'FAILED', updated_at = now() where msg_id = ? and status = 'RETRY_REQUESTED'")
            .param(msgId).update()
    }

    // 발송 도중 크래시로 SENDING에 고착된 건을 다시 발송 대상으로 (L2: 이미 발송됐다면 중복 발송).
    fun recoverStale(olderThanSeconds: Long): Int =
        jdbc.sql("update inbox set status = 'RECEIVED', updated_at = now() where status = 'SENDING' and updated_at < now() - make_interval(secs => ?)")
            .param(olderThanSeconds.toDouble()).update()

    fun countsByStatus(): Map<String, Long> =
        jdbc.sql("select status, count(*) from inbox group by status")
            .query { rs, _ -> rs.getString(1) to rs.getLong(2) }.list().toMap()
}

@RestController
class AlertIntakeController(private val inbox: InboxRepository) {
    @PostMapping("/alerts")
    fun receive(@RequestBody payload: AlertPayload): ResponseEntity<Void> {
        inbox.receive(payload)
        return ResponseEntity.accepted().build()
    }
}
