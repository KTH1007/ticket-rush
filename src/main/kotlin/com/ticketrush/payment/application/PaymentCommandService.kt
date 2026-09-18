package com.ticketrush.payment.application

import com.ticketrush.payment.domain.Payment
import com.ticketrush.payment.domain.PaymentConfirmationResult
import com.ticketrush.payment.domain.PaymentConflictException
import com.ticketrush.payment.domain.PaymentDeclinedException
import com.ticketrush.payment.domain.PaymentGatewayPort
import com.ticketrush.payment.domain.PaymentGatewayResult
import com.ticketrush.payment.domain.PaymentRepositoryPort
import com.ticketrush.payment.domain.PaymentStatus
import com.ticketrush.reservation.SeatPolicyProperties
import com.ticketrush.reservation.domain.HoldTokenMismatchException
import com.ticketrush.reservation.domain.Reservation
import com.ticketrush.reservation.domain.ReservationNotFoundException
import com.ticketrush.reservation.domain.ReservationNotHoldingException
import com.ticketrush.reservation.domain.ReservationRepositoryPort
import com.ticketrush.reservation.domain.ReservationStatus
import com.ticketrush.reservation.domain.SeatRepositoryPort
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime
import java.util.UUID

// 7개 모두 confirmPayment 흐름에서 실제로 쓰이는 의존성이다(리포지토리 3종 + PG + 번호 생성기 + 정책 + Clock). 인위적으로 묶기보다 그대로 둔다.
@Suppress("LongParameterList")
@Service
class PaymentCommandService(
    private val reservationRepository: ReservationRepositoryPort,
    private val seatRepository: SeatRepositoryPort,
    private val paymentRepository: PaymentRepositoryPort,
    private val paymentGateway: PaymentGatewayPort,
    private val reservationNoGenerator: ReservationNoGenerator,
    private val seatPolicy: SeatPolicyProperties,
    private val clock: Clock,
) {
    // 직전에 기록한 실패 처리(Payment FAILED, 홀드 시간 단축)까지 롤백되면 안 되므로,
    // 이 예외만 롤백 대상에서 뺀다(Spring @Transactional의 기본 동작은 RuntimeException 전체 롤백).
    @Transactional(noRollbackFor = [PaymentDeclinedException::class])
    fun confirmPayment(
        reservationId: Long,
        holdToken: UUID,
    ): PaymentConfirmationResult {
        val reservation = loadOwnedReservation(reservationId, holdToken)

        val existingPayment = paymentRepository.findByReservationId(reservationId)
        successResultOrNull(reservation, existingPayment)?.let { return it }

        requireHolding(reservation)

        return chargeAndApply(reservation, existingPayment)
    }

    private fun requireHolding(reservation: Reservation) {
        if (reservation.status != ReservationStatus.HOLDING) throw ReservationNotHoldingException(reservation.status)
        if (!reservation.isHoldActiveAt(LocalDateTime.now(clock))) throw ReservationNotHoldingException(ReservationStatus.EXPIRED)
    }

    private fun loadOwnedReservation(
        reservationId: Long,
        holdToken: UUID,
    ): Reservation {
        val reservation = reservationRepository.findById(reservationId) ?: throw ReservationNotFoundException(reservationId)
        if (reservation.holdToken != holdToken) throw HoldTokenMismatchException()
        return reservation
    }

    private fun successResultOrNull(
        reservation: Reservation,
        payment: Payment?,
    ): PaymentConfirmationResult? {
        if (payment?.status != PaymentStatus.SUCCESS) return null
        val reservationNo = requireNotNull(reservation.reservationNo) { "SUCCESS 결제인데 예약번호가 없습니다: ${reservation.id}" }
        return PaymentConfirmationResult(payment, reservationNo)
    }

    // 정확히 동시에 들어온 요청 중 진 쪽은 여기서 걸림
    private fun chargeAndApply(
        reservation: Reservation,
        existingPayment: Payment?,
    ): PaymentConfirmationResult =
        try {
            when (val result = paymentGateway.charge(reservation.id, reservation.amount, reservation.idempotencyKey)) {
                is PaymentGatewayResult.Approved -> handleApproved(reservation, existingPayment, result)
                is PaymentGatewayResult.Declined -> handleDeclined(reservation, existingPayment, result)
            }
        } catch (e: OptimisticLockingFailureException) {
            throw PaymentConflictException(cause = e)
        } catch (e: DataIntegrityViolationException) {
            if (e.message?.contains("uk_payment_reservation") == true) throw PaymentConflictException()
            throw e
        }

    private fun handleApproved(
        reservation: Reservation,
        existingPayment: Payment?,
        result: PaymentGatewayResult.Approved,
    ): PaymentConfirmationResult {
        // 번호부터 확정한 뒤 상태 전이와 번호 배정을 한 번에 한다. 순서를 바꾸면(상태
        // 전이 먼저) reservation이 "PAID인데 번호는 아직 null"인 중간 상태로 더티 상태가
        // 되고, 그 사이 조회 쿼리(existsByReservationNo)가 Hibernate의 자동 flush를
        // 유발해 그 중간 상태 그대로 저장돼 ck_reservation_no_on_paid에 걸린다(실측 확인).
        val reservationNo = findUnusedReservationNo()
        reservation.confirmPayment()
        reservation.assignReservationNo(reservationNo)
        reservationRepository.save(reservation)
        seatRepository.markSold(reservation.id)

        val payment = existingPayment ?: Payment(reservationId = reservation.id, amount = reservation.amount)
        payment.markSuccess(result.pgTransactionId, LocalDateTime.now(clock))
        return PaymentConfirmationResult(paymentRepository.save(payment), reservationNo)
    }

    // uk_reservation_no 충돌(확률상 사실상 0에 가깝지만 실측 후 재시도로 막기로 함)
    private fun findUnusedReservationNo(): String {
        repeat(MAX_RESERVATION_NO_ATTEMPTS) {
            val candidate = reservationNoGenerator.generate()
            if (!reservationRepository.existsByReservationNo(candidate)) return candidate
        }
        error("예매번호 생성 재시도 초과")
    }

    private fun handleDeclined(
        reservation: Reservation,
        existingPayment: Payment?,
        result: PaymentGatewayResult.Declined,
    ): Nothing {
        val shortened = LocalDateTime.now(clock).plus(seatPolicy.paymentFailedHoldTtl)
        reservation.shortenHoldOnPaymentFailure(shortened)
        reservationRepository.save(reservation)
        seatRepository.shortenHoldExpiry(reservation.id, shortened)

        val payment = existingPayment ?: Payment(reservationId = reservation.id, amount = reservation.amount)
        payment.markFailed()
        paymentRepository.save(payment)
        throw PaymentDeclinedException(result.reason)
    }

    companion object {
        private const val MAX_RESERVATION_NO_ATTEMPTS = 3
    }
}
