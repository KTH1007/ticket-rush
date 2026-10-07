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
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

private val logger = KotlinLogging.logger {}

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
        val wasPending = existing?.status == PaymentStatus.PENDING
        val claimed = if (existing != null) takeOverIfStale(existing, now) else insertNewClaim(reservationId, amount)
        // 이미 확정된 행에 늦게 도착한 시도의 키를 덮어쓰지 않는다
        if (claimed.status != PaymentStatus.PENDING) return claimed
        if (wasPending) recordPaymentKeyChange(claimed, paymentKey)
        claimed.recordAttempt(paymentKey, orderId, now)
        return paymentRepository.save(claimed)
    }

    private fun takeOverIfStale(
        existing: Payment,
        now: LocalDateTime,
    ): Payment {
        if (existing.status == PaymentStatus.FAILED) return reopen(existing)
        if (existing.status != PaymentStatus.PENDING) return existing
        val claimedAt = requireNotNull(existing.updatedAt) { "저장된 Payment는 updatedAt이 있어야 합니다: ${existing.id}" }
        if (claimedAt.plus(policy.staleClaimTimeout).isAfter(now)) throw PaymentConflictException()
        return existing
    }

    // 결과가 불확실한 PENDING을 다른 paymentKey로 덮어쓰면 앞선 결제를 조회할 단서가 사라지므로 이전 값을 이력에 남긴다
    private fun recordPaymentKeyChange(
        claimed: Payment,
        newPaymentKey: String,
    ) {
        val previous = claimed.tossPaymentKey ?: return
        if (previous == newPaymentKey) return
        logger.warn { "오래된 PENDING을 다른 paymentKey로 이어받음: paymentId=${claimed.id}, previous=$previous, new=$newPaymentKey" }
        historyRecorder.record(
            claimed.id,
            PaymentStatus.PENDING,
            PaymentStatus.PENDING,
            reason = "paymentKey 변경: 이전=$previous, 새=$newPaymentKey",
        )
    }

    // 거절된 결제의 재시도는 새 클레임이라 PENDING으로 다시 연다. 이후 응답이 없어도 회수 스케줄러가 대사한다
    private fun reopen(failed: Payment): Payment {
        failed.reopen()
        historyRecorder.record(failed.id, PaymentStatus.FAILED, PaymentStatus.PENDING, reason = "결제 재시도")
        return failed
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
        payment.recordChargeFailure()
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

    // 돈이 움직이는 판단이라 자동 환불하지 않는다. 사람이 처리할 건을 DB에 남겨 재시작해도 사라지지 않고, 같은 건을 다시 조회하지 않게 한다
    @Transactional
    fun markRefundRequired(
        payment: Payment,
        now: LocalDateTime,
        reason: String,
    ) {
        payment.markRefundRequired(now)
        val saved = paymentRepository.save(payment)
        historyRecorder.record(saved.id, PaymentStatus.PENDING, PaymentStatus.PENDING, reason = reason)
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
