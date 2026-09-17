package com.ticketrush.payment.domain

import com.ticketrush.event.domain.EventRepositoryPort
import com.ticketrush.reservation.domain.Reservation
import com.ticketrush.reservation.domain.ReservationRepositoryPort
import com.ticketrush.support.IntegrationTest
import com.ticketrush.support.공연_하나_저장
import com.ticketrush.support.예약_하나_저장
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDateTime
import kotlin.test.Test

class PaymentRepositoryTest : IntegrationTest() {
    @Autowired
    lateinit var eventRepository: EventRepositoryPort

    @Autowired
    lateinit var reservationRepository: ReservationRepositoryPort

    @Autowired
    lateinit var paymentRepository: PaymentRepositoryPort

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `결제를 저장하면 값이 그대로 조회된다`() {
        // given
        val reservation = 예약_하나_저장()

        // when
        val saved = paymentRepository.save(결제(reservation.id))

        // then
        assertThat(saved.reservationId).isEqualTo(reservation.id)
        assertThat(saved.amount).isEqualTo(100_000)
        assertThat(saved.status).isEqualTo(PaymentStatus.PENDING)
    }

    @Test
    fun `reservationId로 결제를 조회한다`() {
        // given
        val reservation = 예약_하나_저장()
        paymentRepository.save(결제(reservation.id))

        // when
        val found = paymentRepository.findByReservationId(reservation.id)

        // then
        assertThat(found).isNotNull
        assertThat(found?.reservationId).isEqualTo(reservation.id)
    }

    @Test
    fun `결제가 없는 예약 id로 조회하면 null을 반환한다`() {
        // when
        val found = paymentRepository.findByReservationId(999_999L)

        // then
        assertThat(found).isNull()
    }

    @Test
    fun `uk_payment_reservation 위반 시(한 예약에 결제 두 건) 예외 발생`() {
        // given
        val reservation = 예약_하나_저장()
        paymentRepository.save(결제(reservation.id))

        // when & then
        assertThatThrownBy { paymentRepository.save(결제(reservation.id)) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
            .hasMessageContaining("uk_payment_reservation")
    }

    @Test
    fun `ck_payment_amount 위반 시 예외 발생`() {
        // given
        val reservation = 예약_하나_저장()

        // when & then
        assertThatThrownBy { paymentRepository.save(결제(reservation.id, amount = -1)) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
            .hasMessageContaining("ck_payment_amount")
    }

    @Test
    fun `ck_payment_success_paid_at 위반 시 예외 발생`() {
        // given
        val reservation = 예약_하나_저장()

        // when & then
        // SUCCESS인데 paid_at이 없는 상태를 DB가 거부하는지 확인
        assertThatThrownBy {
            paymentRepository.save(결제(reservation.id, status = PaymentStatus.SUCCESS, paidAt = null))
        }.isInstanceOf(DataIntegrityViolationException::class.java)
            .hasMessageContaining("ck_payment_success_paid_at")
    }

    @Test
    fun `ck_payment_status 위반 값 저장 시도 시 예외 발생`() {
        // given
        val reservation = 예약_하나_저장()

        // when & then
        // status는 enum이라 Kotlin 타입으로는 잘못된 값을 만들 수 없어 우회 INSERT로 검증
        assertThatThrownBy {
            jdbcTemplate.update(
                """
                INSERT INTO payment (reservation_id, amount, status, created_at, updated_at)
                VALUES (?, 10000, 'INVALID', now(), now())
                """.trimIndent(),
                reservation.id,
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
            .hasMessageContaining("ck_payment_status")
    }

    private fun 예약_하나_저장(): Reservation {
        val event = eventRepository.공연_하나_저장()
        return reservationRepository.예약_하나_저장(event)
    }

    private fun 결제(
        reservationId: Long,
        amount: Int = 100_000,
        status: PaymentStatus = PaymentStatus.PENDING,
        paidAt: LocalDateTime? = null,
    ): Payment =
        Payment(
            reservationId = reservationId,
            amount = amount,
            status = status,
            paidAt = paidAt,
        )
}
