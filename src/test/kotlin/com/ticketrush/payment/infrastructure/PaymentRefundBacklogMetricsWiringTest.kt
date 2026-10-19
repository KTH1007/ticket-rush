package com.ticketrush.payment.infrastructure

import com.ticketrush.event.domain.EventRepositoryPort
import com.ticketrush.payment.PaymentPolicyProperties
import com.ticketrush.payment.domain.Payment
import com.ticketrush.payment.domain.PaymentRepositoryPort
import com.ticketrush.payment.domain.PaymentStatus
import com.ticketrush.reservation.domain.ReservationRepositoryPort
import com.ticketrush.support.IntegrationTest
import com.ticketrush.support.공연_하나_저장
import com.ticketrush.support.예약_하나_저장
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.Test

// 실제 컨텍스트에서 지표 빈이 레지스트리에 묶이고, 갱신이 실제 쿼리로 DB의 건수를 읽는지 본다
class PaymentRefundBacklogMetricsWiringTest : IntegrationTest() {
    @Autowired
    lateinit var metrics: PaymentRefundBacklogMetrics

    @Autowired
    lateinit var meterRegistry: MeterRegistry

    @Autowired
    lateinit var eventRepository: EventRepositoryPort

    @Autowired
    lateinit var reservationRepository: ReservationRepositoryPort

    @Autowired
    lateinit var paymentRepository: PaymentRepositoryPort

    @Autowired
    lateinit var policy: PaymentPolicyProperties

    @Autowired
    lateinit var clock: Clock

    @Test
    @Transactional
    fun `갱신하면 레지스트리의 게이지가 DB의 환불 필요와 소진 건수를 따라간다`() {
        metrics.refresh()
        val requiredBefore = 값("payment.refund.required")
        val exhaustedBefore = 값("payment.refund.exhausted")

        환불_필요_결제()
        소진된_결제()
        소진된_결제()
        metrics.refresh()

        assertThat(값("payment.refund.required")).isEqualTo(requiredBefore + 1)
        assertThat(값("payment.refund.exhausted")).isEqualTo(exhaustedBefore + 2)
    }

    private fun 값(name: String): Double = meterRegistry.get(name).gauge().value()

    private fun 환불_필요_결제() {
        val payment = 결제(PaymentStatus.PENDING, refundAttemptCount = 0)
        payment.markRefundRequired(LocalDateTime.now(clock))
        paymentRepository.save(payment)
    }

    private fun 소진된_결제() {
        결제(PaymentStatus.CANCELED, refundAttemptCount = policy.refundMaxAttempts)
    }

    private fun 결제(
        status: PaymentStatus,
        refundAttemptCount: Int,
    ): Payment {
        val reservation = reservationRepository.예약_하나_저장(eventRepository.공연_하나_저장())
        return paymentRepository.save(
            Payment(
                reservationId = reservation.id,
                amount = 100_000,
                status = status,
                tossPaymentKey = "pk-${UUID.randomUUID()}".takeIf { status == PaymentStatus.PENDING },
                pgTransactionId = "pk-${UUID.randomUUID()}".takeIf { status != PaymentStatus.PENDING },
                refundAttemptCount = refundAttemptCount,
            ),
        )
    }
}
