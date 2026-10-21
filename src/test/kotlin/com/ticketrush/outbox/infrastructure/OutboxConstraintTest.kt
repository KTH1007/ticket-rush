package com.ticketrush.outbox.infrastructure

import com.ticketrush.outbox.domain.OutboxEvent
import com.ticketrush.outbox.domain.OutboxRepositoryPort
import com.ticketrush.outbox.domain.OutboxStatus
import com.ticketrush.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDateTime
import kotlin.test.Test

class OutboxConstraintTest : IntegrationTest() {
    @Autowired
    lateinit var outboxRepository: OutboxRepositoryPort

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    private val now = LocalDateTime.of(2026, 1, 1, 0, 0)

    @Test
    fun `허용되지 않는 status 값은 저장할 수 없다`() {
        // status는 enum이라 Kotlin 타입으로는 잘못된 값을 만들 수 없어 우회 INSERT로 검증.
        // ck_outbox_status와 ck_outbox_claim_state가 같이 걸려 이름순으로 앞서는 쪽이 보고된다
        assertThatThrownBy {
            jdbcTemplate.update(
                """
                INSERT INTO outbox (aggregate_type, aggregate_id, event_type, payload, status, attempt_count, next_attempt_at, created_at)
                VALUES ('RESERVATION', 1, 'RESERVATION_PAID', '{}'::jsonb, 'INVALID', 0, now(), now())
                """.trimIndent(),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
            .hasMessageContaining("ck_outbox_claim_state")
    }

    @Test
    fun `ck_outbox_attempt_count 위반 시 예외 발생`() {
        assertThatThrownBy {
            outboxRepository.save(이벤트(attemptCount = -1))
        }.isInstanceOf(DataIntegrityViolationException::class.java)
            .hasMessageContaining("ck_outbox_attempt_count")
    }

    @Test
    fun `ck_outbox_claim_state 위반 시 PENDING인데 claimed_at이 있으면 예외 발생`() {
        assertThatThrownBy {
            outboxRepository.save(이벤트(status = OutboxStatus.PENDING, claimedAt = now))
        }.isInstanceOf(DataIntegrityViolationException::class.java)
            .hasMessageContaining("ck_outbox_claim_state")
    }

    @Test
    fun `ck_outbox_claim_state 위반 시 PROCESSING인데 claimed_at이 없으면 예외 발생`() {
        assertThatThrownBy {
            outboxRepository.save(이벤트(status = OutboxStatus.PROCESSING, claimedAt = null))
        }.isInstanceOf(DataIntegrityViolationException::class.java)
            .hasMessageContaining("ck_outbox_claim_state")
    }

    private fun 이벤트(
        status: OutboxStatus = OutboxStatus.PENDING,
        attemptCount: Int = 0,
        claimedAt: LocalDateTime? = null,
    ): OutboxEvent =
        OutboxEvent(
            aggregateType = "RESERVATION",
            aggregateId = 1L,
            eventType = "RESERVATION_PAID",
            payload = """{"reservationId":1}""",
            status = status,
            attemptCount = attemptCount,
            nextAttemptAt = now,
            claimedAt = claimedAt,
            createdAt = now,
        )
}
