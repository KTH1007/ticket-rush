package com.ticketrush.reservation.domain

import com.ticketrush.event.domain.EventRepositoryPort
import com.ticketrush.shared.PhoneHash
import com.ticketrush.support.IntegrationTest
import com.ticketrush.support.공연_하나_저장
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.Test

class ReservationRepositoryTest : IntegrationTest() {
    @Autowired
    lateinit var eventRepository: EventRepositoryPort

    @Autowired
    lateinit var reservationRepository: ReservationRepositoryPort

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `예약을 저장하면 값이 그대로 조회된다`() {
        // given
        val event = eventRepository.공연_하나_저장()
        val phoneHash = PhoneHash(ByteArray(32) { 1 })

        // when
        val saved = reservationRepository.save(예약(eventId = event.id, phoneHash = phoneHash))

        // then
        assertThat(saved.eventId).isEqualTo(event.id)
        assertThat(saved.phoneHash).isEqualTo(phoneHash)
        assertThat(saved.status).isEqualTo(ReservationStatus.HOLDING)
        assertThat(saved.reservationNo).isNull()
    }

    @Test
    fun `uk_reservation_no 위반 시 예외 발생`() {
        // given
        val event = eventRepository.공연_하나_저장()
        reservationRepository.save(예약(eventId = event.id, reservationNo = "RES00000001"))

        // when & then
        assertThatThrownBy {
            reservationRepository.save(예약(eventId = event.id, reservationNo = "RES00000001"))
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `uk_reservation_idem 위반 시 예외 발생`() {
        // given
        val event = eventRepository.공연_하나_저장()
        val idempotencyKey = UUID.randomUUID()
        reservationRepository.save(예약(eventId = event.id, idempotencyKey = idempotencyKey))

        // when & then
        assertThatThrownBy {
            reservationRepository.save(예약(eventId = event.id, idempotencyKey = idempotencyKey))
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `ck_reservation_qty 위반 값 저장 시도 시 예외 발생`() {
        // given
        val event = eventRepository.공연_하나_저장()

        // when & then
        assertThatThrownBy {
            reservationRepository.save(예약(eventId = event.id, quantity = 3))
        }.isInstanceOf(DataIntegrityViolationException::class.java)
            .hasMessageContaining("ck_reservation_qty")
    }

    @Test
    fun `ck_reservation_no_on_paid 위반 시 예외 발생`() {
        // given
        val event = eventRepository.공연_하나_저장()

        // when & then
        // PAID인데 예매번호가 없는 상태를 DB가 거부하는지 확인
        assertThatThrownBy {
            reservationRepository.save(
                예약(eventId = event.id, status = ReservationStatus.PAID, reservationNo = null),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
            .hasMessageContaining("ck_reservation_no_on_paid")
    }

    @Test
    fun `ck_reservation_status 위반 값 저장 시도 시 예외 발생`() {
        // given
        val event = eventRepository.공연_하나_저장()

        // when & then
        // status는 enum이라 Kotlin 타입으로는 잘못된 값을 만들 수 없어 우회 INSERT로 검증
        assertThatThrownBy {
            jdbcTemplate.update(
                """
                INSERT INTO reservation
                    (event_id, idempotency_key, phone_hash, quantity, amount, status, hold_token, created_at, updated_at)
                VALUES (?, ?, ?, 1, 10000, 'INVALID', ?, now(), now())
                """.trimIndent(),
                event.id,
                UUID.randomUUID(),
                ByteArray(32) { 1 },
                UUID.randomUUID(),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
            .hasMessageContaining("ck_reservation_status")
    }

    private fun 예약(
        eventId: Long,
        phoneHash: PhoneHash = PhoneHash(ByteArray(32) { 1 }),
        quantity: Short = 1,
        amount: Int = 100_000,
        holdToken: UUID = UUID.randomUUID(),
        idempotencyKey: UUID = UUID.randomUUID(),
        reservationNo: String? = null,
        status: ReservationStatus = ReservationStatus.HOLDING,
        // ck_reservation_holding_expiry: HOLDING이면 hold_expires_at이 반드시 있어야 한다.
        // NOT NULL 여부만 검증하는 제약이라 특정 미래 시각일 필요는 없어 고정값을 쓴다.
        holdExpiresAt: LocalDateTime? = if (status == ReservationStatus.HOLDING) FIXED_HOLD_EXPIRES_AT else null,
    ): Reservation =
        Reservation(
            eventId = eventId,
            phoneHash = phoneHash,
            quantity = quantity,
            amount = amount,
            holdToken = holdToken,
            idempotencyKey = idempotencyKey,
            reservationNo = reservationNo,
            status = status,
            holdExpiresAt = holdExpiresAt,
        )

    companion object {
        private val FIXED_HOLD_EXPIRES_AT: LocalDateTime = LocalDateTime.of(2030, 1, 1, 0, 0)
    }
}
