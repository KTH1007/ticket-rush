package com.ticketrush.payment.application

import com.ticketrush.payment.PaymentPolicyProperties
import com.ticketrush.payment.domain.Payment
import com.ticketrush.payment.domain.PaymentGatewayPort
import com.ticketrush.payment.domain.PaymentGatewayResult
import com.ticketrush.payment.domain.PaymentRepositoryPort
import com.ticketrush.payment.domain.PaymentStatus
import com.ticketrush.reservation.domain.Reservation
import com.ticketrush.reservation.domain.ReservationRepositoryPort
import com.ticketrush.reservation.domain.ReservationStatus
import com.ticketrush.shared.PhoneHash
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test

// 목록 조회와 처리 사이의 경쟁, 반영 단계의 예외, 틱 겹침은 실제 DB 테스트로 만들 수 없어 협력 객체를 목으로 대체한다
class PaymentRefundRetrySchedulerRaceTest {
    private val paymentRepository = mockk<PaymentRepositoryPort>()
    private val reservationRepository = mockk<ReservationRepositoryPort>()
    private val paymentGateway = mockk<PaymentGatewayPort>()
    private val recorder = mockk<PaymentRefundRecorder>()
    private val clock = Clock.fixed(Instant.parse("2026-10-03T00:00:00Z"), ZoneOffset.UTC)
    private val scheduler =
        PaymentRefundRetryScheduler(
            paymentRepository,
            reservationRepository,
            paymentGateway,
            recorder,
            PaymentPolicyProperties(staleClaimTimeout = Duration.ofSeconds(10), reclaimInterval = Duration.ofSeconds(5)),
            clock,
        )

    @Test
    fun `재조회했을 때 결제가 이미 REFUNDED면 환불을 다시 요청하지 않는다`() {
        // given: 목록을 읽은 뒤 다른 인스턴스가 먼저 환불을 끝낸 상황
        every { paymentRepository.findStaleCanceled(any(), any()) } returns listOf(결제(1L, 10L, PaymentStatus.CANCELED))
        every { paymentRepository.findByReservationId(10L) } returns 결제(1L, 10L, PaymentStatus.REFUNDED)

        // when
        scheduler.retry(LocalDateTime.now(clock))

        // then
        verify(exactly = 0) { paymentGateway.refund(any(), any(), any()) }
    }

    @Test
    fun `반영 단계에서 예외가 나도 다음 행은 계속 처리한다`() {
        // given: 첫 행은 Toss 환불은 됐는데 DB 반영이 실패하는 상황
        stubRow(1L, 10L)
        stubRow(2L, 11L)
        every { paymentRepository.findStaleCanceled(any(), any()) } returns
            listOf(결제(1L, 10L, PaymentStatus.CANCELED), 결제(2L, 11L, PaymentStatus.CANCELED))
        every { recorder.recordSuccess(match { it.id == 1L }, any()) } throws IllegalStateException("낙관적 락 충돌")
        justRun { recorder.recordSuccess(match { it.id == 2L }, any()) }

        // when
        scheduler.retry(LocalDateTime.now(clock))

        // then
        verify(exactly = 1) { recorder.recordSuccess(match { it.id == 2L }, "pk-2") }
    }

    @Test
    fun `이전 틱이 아직 돌고 있으면 같은 인스턴스의 다음 틱은 환불을 요청하지 않고 끝난다`() {
        // given: 환불 호출이 길어지는 사이 다음 틱이 들어온 상황
        stubRow(1L, 10L)
        every { paymentRepository.findStaleCanceled(any(), any()) } returns listOf(결제(1L, 10L, PaymentStatus.CANCELED))
        justRun { recorder.recordSuccess(any(), any()) }
        every { paymentGateway.refund(any(), any(), any()) } answers {
            scheduler.retry(LocalDateTime.now(clock))
            PaymentGatewayResult.Approved(pgTransactionId = "pk-1")
        }

        // when
        scheduler.retry(LocalDateTime.now(clock))

        // then
        verify(exactly = 1) { paymentGateway.refund(any(), any(), any()) }
    }

    private fun stubRow(
        paymentId: Long,
        reservationId: Long,
    ) {
        every { paymentRepository.findByReservationId(reservationId) } returns 결제(paymentId, reservationId, PaymentStatus.CANCELED)
        every { reservationRepository.findById(reservationId) } returns 예약(reservationId)
        every { paymentGateway.refund("pk-$paymentId", any(), any()) } returns
            PaymentGatewayResult.Approved(pgTransactionId = "pk-$paymentId")
    }

    private fun 결제(
        id: Long,
        reservationId: Long,
        status: PaymentStatus,
    ): Payment =
        Payment(
            id = id,
            reservationId = reservationId,
            amount = AMOUNT,
            status = status,
            pgTransactionId = "pk-$id",
        )

    private fun 예약(id: Long): Reservation =
        Reservation(
            id = id,
            eventId = 1L,
            phoneHash = PhoneHash(ByteArray(32) { 1 }),
            quantity = 1,
            amount = AMOUNT,
            holdToken = UUID.randomUUID(),
            idempotencyKey = UUID.randomUUID(),
            status = ReservationStatus.CANCELED,
            holdExpiresAt = LocalDateTime.of(2030, 1, 1, 0, 0),
        )

    companion object {
        private const val AMOUNT = 100_000
    }
}
