package com.ticketrush.shared

import com.ticketrush.event.domain.EventRepositoryPort
import com.ticketrush.support.TransactionalIntegrationTest
import com.ticketrush.support.공연_하나_저장
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import kotlin.test.Test

class ConstraintViolationsTest : TransactionalIntegrationTest() {
    @Autowired
    lateinit var eventRepository: EventRepositoryPort

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `유니크 위반이면 위반된 제약 이름과 같을 때만 true다`() {
        // given
        val eventId = eventRepository.공연_하나_저장().id
        val idempotencyKey = UUID.randomUUID()
        예약_삽입(eventId, idempotencyKey)

        // when
        val failure = requireNotNull(catchThrowable { 예약_삽입(eventId, idempotencyKey) })

        // then
        assertThat(failure).isInstanceOf(DataIntegrityViolationException::class.java)
        assertThat(failure.isUniqueViolation("uk_reservation_idem")).isTrue()
        assertThat(failure.isUniqueViolation("uk_reservation_no")).isFalse()
    }

    @Test
    fun `CHECK 위반은 제약 이름이 같아도 유니크 위반이 아니다`() {
        // given
        val eventId = eventRepository.공연_하나_저장().id

        // when
        val failure = requireNotNull(catchThrowable { 예약_삽입(eventId, UUID.randomUUID(), quantity = 3) })

        // then
        assertThat(failure).isInstanceOf(DataIntegrityViolationException::class.java)
        assertThat(failure.isUniqueViolation("ck_reservation_qty")).isFalse()
    }

    @Test
    fun `메시지에 제약 이름이 있어도 DB 위반이 아니면 false다`() {
        // given
        val failure = DataIntegrityViolationException("duplicate key value violates unique constraint \"uk_reservation_idem\"")

        // then
        assertThat(failure.isUniqueViolation("uk_reservation_idem")).isFalse()
    }

    private fun 예약_삽입(
        eventId: Long,
        idempotencyKey: UUID,
        quantity: Int = 1,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO reservation (event_id, idempotency_key, phone_hash, quantity, amount, status, hold_token,
                                     hold_expires_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, 10000, 'HOLDING', ?, now(), now(), now())
            """.trimIndent(),
            eventId,
            idempotencyKey,
            ByteArray(32) { 1 },
            quantity,
            UUID.randomUUID(),
        )
    }
}
