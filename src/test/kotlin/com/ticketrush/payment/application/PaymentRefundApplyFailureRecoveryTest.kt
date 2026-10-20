package com.ticketrush.payment.application

import com.ticketrush.payment.PaymentPolicyProperties
import com.ticketrush.payment.domain.Payment
import com.ticketrush.payment.domain.PaymentGatewayPort
import com.ticketrush.payment.domain.PaymentGatewayResult
import com.ticketrush.payment.domain.PaymentRepositoryPort
import com.ticketrush.payment.domain.PaymentStatus
import com.ticketrush.payment.domain.RefundIdempotencyKey
import com.ticketrush.reservation.domain.Reservation
import com.ticketrush.reservation.domain.ReservationRepositoryPort
import com.ticketrush.reservation.domain.ReservationStatus
import com.ticketrush.shared.PhoneHash
import com.ticketrush.support.testClientIp
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test

// 환불은 Toss에서 끝났는데 결과를 DB에 못 적은 경우, 재시도 스케줄러가 같은 키로 다시 요청해 REFUNDED까지 가는지 본다.
// 같은 키라서 Toss가 이중 환불 없이 이미 처리된 결과를 돌려준다는 전제다
class PaymentRefundApplyFailureRecoveryTest {
    private val paymentGateway = mockk<PaymentGatewayPort>()
    private val refunder = PaymentRefunder(paymentGateway)
    private val cancelRecorder = mockk<PaymentCancelRecorder>()
    private val refundRecorder = mockk<PaymentRefundRecorder>()
    private val paymentRepository = mockk<PaymentRepositoryPort>()
    private val reservationRepository = mockk<ReservationRepositoryPort>()
    private val clock = Clock.fixed(Instant.parse("2026-10-19T00:00:00Z"), ZoneOffset.UTC)
    private val reservationKey = UUID.randomUUID()
    private val payment = 취소된_결제()

    private val cancelService = PaymentCancelCommandService(cancelRecorder, refunder, refundRecorder, paymentRepository)
    private val scheduler =
        PaymentRefundRetryScheduler(
            paymentRepository,
            reservationRepository,
            refunder,
            refundRecorder,
            PaymentPolicyProperties(staleClaimTimeout = Duration.ofSeconds(10), reclaimInterval = Duration.ofSeconds(5)),
            clock,
        )

    @Test
    fun `환불은 승인됐는데 반영이 실패하면 스케줄러가 같은 키로 다시 요청해 REFUNDED로 반영한다`() {
        every { cancelRecorder.confirmCancel(RESERVATION_NO, PHONE, testClientIp) } returns CanceledPayment(payment, reservationKey)
        every { paymentGateway.refund("PG-TXN", AMOUNT, any()) } returns PaymentGatewayResult.Approved("PG-TXN")
        every { refundRecorder.recordCancelSuccess(any(), any()) } throws IllegalStateException("DB 오류")
        every { paymentRepository.findByReservationId(RESERVATION_ID) } returns payment

        val canceled = cancelService.cancel(RESERVATION_NO, PHONE, testClientIp)

        assertThat(canceled.payment.status).isEqualTo(PaymentStatus.CANCELED)
        assertThat(canceled.payment.refundAttemptCount).isEqualTo(0)

        every { paymentRepository.findStaleCanceled(any(), any()) } returns listOf(payment)
        every { reservationRepository.findById(RESERVATION_ID) } returns 예약()
        justRun { refundRecorder.recordSuccess(any(), any()) }

        scheduler.retry(LocalDateTime.now(clock))

        val firstKey = RefundIdempotencyKey.of(reservationKey, 0)
        verify(exactly = 2) { paymentGateway.refund("PG-TXN", AMOUNT, firstKey) }
        verify(exactly = 1) { refundRecorder.recordSuccess(payment, PaymentGatewayResult.Approved("PG-TXN")) }
    }

    private fun 취소된_결제(): Payment =
        Payment(id = 1L, reservationId = RESERVATION_ID, amount = AMOUNT).apply {
            markSuccess("PG-TXN", LocalDateTime.of(2026, 1, 1, 0, 0))
            markCanceled()
        }

    private fun 예약(): Reservation =
        Reservation(
            id = RESERVATION_ID,
            eventId = 1L,
            phoneHash = PhoneHash(ByteArray(32) { 1 }),
            quantity = 1,
            amount = AMOUNT,
            holdToken = UUID.randomUUID(),
            idempotencyKey = reservationKey,
            status = ReservationStatus.CANCELED,
            holdExpiresAt = LocalDateTime.of(2030, 1, 1, 0, 0),
        )

    companion object {
        private const val RESERVATION_ID = 10L
        private const val AMOUNT = 200_000
        private const val RESERVATION_NO = "RESNO0000001"
        private const val PHONE = "01011112222"
    }
}
