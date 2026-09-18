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
import java.time.LocalDateTime
import kotlin.test.Test

class PaymentHistoryRepositoryTest : IntegrationTest() {
    @Autowired
    lateinit var eventRepository: EventRepositoryPort

    @Autowired
    lateinit var reservationRepository: ReservationRepositoryPort

    @Autowired
    lateinit var paymentRepository: PaymentRepositoryPort

    @Autowired
    lateinit var paymentHistoryRepository: PaymentHistoryRepositoryPort

    @Test
    fun `결제 이력을 저장하면 값이 그대로 조회된다`() {
        // given
        val payment = 결제_하나_저장()

        // when
        val saved = 이력_저장(payment.id, PaymentStatus.PENDING, PaymentStatus.SUCCESS)

        // then
        assertThat(saved.paymentId).isEqualTo(payment.id)
        assertThat(saved.fromStatus).isEqualTo(PaymentStatus.PENDING)
        assertThat(saved.toStatus).isEqualTo(PaymentStatus.SUCCESS)
    }

    @Test
    fun `fromStatus가 없어도(최초 생성) 저장할 수 있다`() {
        // given
        val payment = 결제_하나_저장()

        // when
        val saved = 이력_저장(payment.id, null, PaymentStatus.PENDING)

        // then
        assertThat(saved.fromStatus).isNull()
    }

    @Test
    fun `존재하지 않는 payment_id로 저장하면 예외가 발생한다`() {
        // when & then
        assertThatThrownBy { 이력_저장(999_999L, null, PaymentStatus.PENDING) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `payment_id로 이력 목록을 조회한다`() {
        // given
        val payment = 결제_하나_저장()
        이력_저장(payment.id, null, PaymentStatus.PENDING)
        이력_저장(payment.id, PaymentStatus.PENDING, PaymentStatus.SUCCESS)

        // when
        val histories = paymentHistoryRepository.findAllByPaymentId(payment.id)

        // then
        assertThat(histories).hasSize(2)
        assertThat(histories).extracting("toStatus").containsExactlyInAnyOrder(PaymentStatus.PENDING, PaymentStatus.SUCCESS)
    }

    private fun 결제_하나_저장(): Payment {
        val reservation = 예약_하나_저장()
        return paymentRepository.save(Payment(reservationId = reservation.id, amount = 100_000))
    }

    private fun 예약_하나_저장(): Reservation {
        val event = eventRepository.공연_하나_저장()
        return reservationRepository.예약_하나_저장(event)
    }

    private fun 이력_저장(
        paymentId: Long,
        from: PaymentStatus?,
        to: PaymentStatus,
    ): PaymentHistory =
        paymentHistoryRepository.save(
            PaymentHistory(paymentId = paymentId, fromStatus = from, toStatus = to, createdAt = LocalDateTime.of(2026, 1, 1, 0, 0)),
        )
}
