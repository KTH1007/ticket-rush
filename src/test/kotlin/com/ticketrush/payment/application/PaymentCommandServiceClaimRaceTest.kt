package com.ticketrush.payment.application

import com.ticketrush.payment.domain.Payment
import com.ticketrush.payment.domain.PaymentConflictException
import com.ticketrush.payment.domain.PaymentGatewayPort
import com.ticketrush.payment.domain.PaymentRepositoryPort
import com.ticketrush.reservation.domain.Reservation
import com.ticketrush.reservation.domain.ReservationRepositoryPort
import com.ticketrush.reservation.domain.ReservationStatus
import com.ticketrush.shared.PhoneHash
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.springframework.orm.ObjectOptimisticLockingFailureException
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test

// 같은 결제를 동시에 이어받거나 다시 열다 지는 경쟁은 실제 DB 테스트로 결정적으로 만들 수 없어 협력 객체를 목으로 대체한다
class PaymentCommandServiceClaimRaceTest {
    private val reservationRepository = mockk<ReservationRepositoryPort>()
    private val paymentRepository = mockk<PaymentRepositoryPort>()
    private val paymentGateway = mockk<PaymentGatewayPort>()
    private val claimService = mockk<PaymentClaimService>()
    private val clock = Clock.fixed(Instant.parse("2026-10-06T00:00:00Z"), ZoneOffset.UTC)
    private val service = PaymentCommandService(reservationRepository, paymentRepository, paymentGateway, claimService, clock)

    @Test
    fun `클레임을 이어받다 낙관적 락에 지면 서버 오류가 아니라 충돌 예외로 응답하고 PG는 부르지 않는다`() {
        val reservation = 예약()
        every { reservationRepository.findById(RESERVATION_ID) } returns reservation
        every { paymentRepository.findByReservationId(RESERVATION_ID) } returns null
        every { claimService.claimOrTakeOver(any(), any(), any(), any(), any()) } throws
            ObjectOptimisticLockingFailureException(Payment::class.java, 1L)

        assertThatThrownBy {
            service.confirmPayment(RESERVATION_ID, reservation.holdToken, "pk-race", reservation.idempotencyKey.toString(), AMOUNT)
        }.isInstanceOf(PaymentConflictException::class.java)

        verify(exactly = 0) { paymentGateway.charge(any(), any(), any(), any()) }
    }

    private fun 예약(): Reservation =
        Reservation(
            id = RESERVATION_ID,
            eventId = 1L,
            phoneHash = PhoneHash(ByteArray(32) { 1 }),
            quantity = 1,
            amount = AMOUNT,
            holdToken = UUID.randomUUID(),
            idempotencyKey = UUID.randomUUID(),
            status = ReservationStatus.HOLDING,
            holdExpiresAt = LocalDateTime.of(2030, 1, 1, 0, 0),
        )

    companion object {
        private const val RESERVATION_ID = 10L
        private const val AMOUNT = 100_000
    }
}
