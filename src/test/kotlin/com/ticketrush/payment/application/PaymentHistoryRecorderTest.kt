package com.ticketrush.payment.application

import com.ticketrush.event.domain.EventRepositoryPort
import com.ticketrush.payment.domain.Payment
import com.ticketrush.payment.domain.PaymentHistoryRepositoryPort
import com.ticketrush.payment.domain.PaymentRepositoryPort
import com.ticketrush.payment.domain.PaymentStatus
import com.ticketrush.reservation.domain.ReservationRepositoryPort
import com.ticketrush.support.IntegrationTest
import com.ticketrush.support.공연_하나_저장
import com.ticketrush.support.예약_하나_저장
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.Test

class PaymentHistoryRecorderTest : IntegrationTest() {
    @Autowired
    lateinit var eventRepository: EventRepositoryPort

    @Autowired
    lateinit var reservationRepository: ReservationRepositoryPort

    @Autowired
    lateinit var paymentRepository: PaymentRepositoryPort

    @Autowired
    lateinit var paymentHistoryRepository: PaymentHistoryRepositoryPort

    @Autowired
    lateinit var recorder: PaymentHistoryRecorder

    @Test
    fun `기록하면 payment_history에 한 건 쌓인다`() {
        // given
        val payment = 결제_하나_저장()

        // when
        recorder.record(paymentId = payment.id, from = PaymentStatus.PENDING, to = PaymentStatus.SUCCESS, reason = "테스트")

        // then
        val history = paymentHistoryRepository.findAllByPaymentId(payment.id)
        assertThat(history).hasSize(1)
        assertThat(history.single().toStatus).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(history.single().reason).isEqualTo("테스트")
    }

    @Test
    fun `fromStatus 없이(최초) 기록할 수 있다`() {
        // given
        val payment = 결제_하나_저장()

        // when
        recorder.record(paymentId = payment.id, from = null, to = PaymentStatus.PENDING)

        // then
        val history = paymentHistoryRepository.findAllByPaymentId(payment.id)
        assertThat(history.single().fromStatus).isNull()
    }

    private fun 결제_하나_저장(): Payment {
        val event = eventRepository.공연_하나_저장()
        val reservation = reservationRepository.예약_하나_저장(event)
        return paymentRepository.save(Payment(reservationId = reservation.id, amount = 100_000))
    }
}
