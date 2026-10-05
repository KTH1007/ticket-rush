package com.ticketrush.payment.application

import com.ticketrush.payment.PaymentPolicyProperties
import com.ticketrush.payment.domain.Payment
import com.ticketrush.payment.domain.PaymentConfirmationResult
import com.ticketrush.payment.domain.PaymentConflictException
import com.ticketrush.payment.domain.PaymentRepositoryPort
import com.ticketrush.payment.domain.PaymentStatus
import com.ticketrush.payment.domain.ReservationPaidEvent
import com.ticketrush.reservation.SeatPolicyProperties
import com.ticketrush.reservation.domain.Reservation
import com.ticketrush.reservation.domain.ReservationRepositoryPort
import com.ticketrush.reservation.domain.SeatRepositoryPort
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

// claim(트랜잭션 1)과 결과 반영(트랜잭션 2)을 PaymentCommandService와 분리된 빈으로 둔다.
@Suppress("LongParameterList")
@Component
class PaymentClaimService(
    private val paymentRepository: PaymentRepositoryPort,
    private val reservationRepository: ReservationRepositoryPort,
    private val seatRepository: SeatRepositoryPort,
    private val reservationNoGenerator: ReservationNoGenerator,
    private val seatPolicy: SeatPolicyProperties,
    private val historyRecorder: PaymentHistoryRecorder,
    private val eventPublisher: ApplicationEventPublisher,
    private val policy: PaymentPolicyProperties,
) {
    @Transactional
    fun claimOrTakeOver(
        reservationId: Long,
        amount: Int,
        paymentKey: String,
        orderId: String,
        now: LocalDateTime,
    ): Payment {
        val existing = paymentRepository.findByReservationId(reservationId)
        val claimed = if (existing != null) takeOverIfStale(existing, now) else insertNewClaim(reservationId, amount)
        // 이미 확정된 행에 늦게 도착한 시도의 키를 덮어쓰지 않는다
        if (claimed.status != PaymentStatus.PENDING) return claimed
        claimed.recordAttempt(paymentKey, orderId)
        return paymentRepository.save(claimed)
    }

    private fun takeOverIfStale(
        existing: Payment,
        now: LocalDateTime,
    ): Payment {
        if (existing.status != PaymentStatus.PENDING) return existing
        val claimedAt = requireNotNull(existing.updatedAt) { "저장된 Payment는 updatedAt이 있어야 합니다: ${existing.id}" }
        if (claimedAt.plus(policy.staleClaimTimeout).isAfter(now)) throw PaymentConflictException()
        return existing
    }

    private fun insertNewClaim(
        reservationId: Long,
        amount: Int,
    ): Payment =
        try {
            paymentRepository.save(Payment(reservationId = reservationId, amount = amount))
        } catch (e: DataIntegrityViolationException) {
            if (e.message?.contains("uk_payment_reservation") == true) throw PaymentConflictException(cause = e)
            throw e
        }

    // 번호부터 확정한 뒤 상태 전이와 번호 배정을 한 번에 한다. 순서를 바꾸면 PAID인데
    // 번호는 없는 중간 상태로 flush될 수 있어 제약 위반이 난다.
    @Transactional
    fun applySuccess(
        payment: Payment,
        reservation: Reservation,
        pgTransactionId: String,
        paidAt: LocalDateTime,
    ): PaymentConfirmationResult {
        val reservationNo = findUnusedReservationNo()
        reservation.confirmPayment()
        reservation.assignReservationNo(reservationNo)
        reservationRepository.save(reservation)
        seatRepository.markSold(reservation.id)

        val fromStatus = payment.status
        payment.markSuccess(pgTransactionId, paidAt)
        val saved = paymentRepository.save(payment)
        historyRecorder.record(saved.id, fromStatus, PaymentStatus.SUCCESS)
        eventPublisher.publishEvent(ReservationPaidEvent(reservation.id))
        return PaymentConfirmationResult(saved, reservationNo)
    }

    @Transactional
    fun applyFailure(
        payment: Payment,
        reservation: Reservation,
        now: LocalDateTime,
        reason: String,
    ) {
        val shortened = now.plus(seatPolicy.paymentFailedHoldTtl)
        reservation.shortenHoldOnPaymentFailure(shortened)
        reservationRepository.save(reservation)
        seatRepository.shortenHoldExpiry(reservation.id, shortened)

        val fromStatus = payment.status
        payment.markFailed()
        val saved = paymentRepository.save(payment)
        historyRecorder.record(saved.id, fromStatus, PaymentStatus.FAILED, reason = reason)
    }

    // 예약이 이미 쓸 수 없는 상태라 applyFailure처럼 홀드를 단축하지 않고 결제 상태만 닫는다
    @Transactional
    fun markFailedByReconciliation(
        payment: Payment,
        reason: String,
    ) {
        val fromStatus = payment.status
        payment.markFailed()
        val saved = paymentRepository.save(payment)
        historyRecorder.record(saved.id, fromStatus, PaymentStatus.FAILED, reason = reason)
    }

    private fun findUnusedReservationNo(): String {
        repeat(MAX_RESERVATION_NO_ATTEMPTS) {
            val candidate = reservationNoGenerator.generate()
            if (!reservationRepository.existsByReservationNo(candidate)) return candidate
        }
        error("예매번호 생성 재시도 초과")
    }

    companion object {
        private const val MAX_RESERVATION_NO_ATTEMPTS = 3
    }
}
