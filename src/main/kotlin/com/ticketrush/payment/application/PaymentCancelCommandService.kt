package com.ticketrush.payment.application

import com.ticketrush.payment.domain.Payment
import com.ticketrush.payment.domain.PaymentCancelResult
import com.ticketrush.payment.domain.PaymentConflictException
import com.ticketrush.payment.domain.PaymentGatewayPort
import com.ticketrush.payment.domain.PaymentGatewayResult
import com.ticketrush.payment.domain.PaymentRepositoryPort
import com.ticketrush.payment.domain.PaymentStatus
import com.ticketrush.reservation.domain.Reservation
import com.ticketrush.reservation.domain.ReservationAlreadyCanceledException
import com.ticketrush.reservation.domain.ReservationLookupFailedException
import com.ticketrush.reservation.domain.ReservationLookupRateLimitedException
import com.ticketrush.reservation.domain.ReservationLookupRateLimiterPort
import com.ticketrush.reservation.domain.ReservationRepositoryPort
import com.ticketrush.reservation.domain.ReservationStatus
import com.ticketrush.reservation.domain.SeatRepositoryPort
import com.ticketrush.shared.PhoneHasher
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

// 7개 모두 cancel 흐름에서 실제로 쓰이는 의존성이다(리포지토리 3종 + PG + 레이트리미터 + 해셔 + 이력 기록기).
@Suppress("LongParameterList")
@Service
class PaymentCancelCommandService(
    private val reservationRepository: ReservationRepositoryPort,
    private val seatRepository: SeatRepositoryPort,
    private val paymentRepository: PaymentRepositoryPort,
    private val paymentGateway: PaymentGatewayPort,
    private val rateLimiter: ReservationLookupRateLimiterPort,
    private val phoneHasher: PhoneHasher,
    private val historyRecorder: PaymentHistoryRecorder,
) {
    @Transactional
    fun cancel(
        reservationNo: String,
        phone: String,
    ): PaymentCancelResult {
        val reservation = verifyOwnership(reservationNo, phone)
        requireCancelable(reservation)
        val payment = confirmCancelAndRefund(reservation)
        return PaymentCancelResult(payment, reservationNo)
    }

    // #83과 같은 패턴: 확인은 DB 조회 전에 원자적으로 먼저(차단 여부), 실패해야만 기록,
    // 성공해야만 반환. reservationNo가 없든 phone이 틀리든 같은 예외로 합쳐 존재 여부를 숨긴다
    private fun verifyOwnership(
        reservationNo: String,
        phone: String,
    ): Reservation {
        if (!rateLimiter.tryReserveAttempt(reservationNo)) throw ReservationLookupRateLimitedException()

        val reservation = reservationRepository.findByReservationNo(reservationNo)
        if (reservation == null || reservation.phoneHash != phoneHasher.hash(phone)) {
            throw ReservationLookupFailedException()
        }

        rateLimiter.releaseAttempt(reservationNo)
        return reservation
    }

    // reservationNo는 PAID 전이 시점에만 배정되고 이후 지워지지 않는다(assignReservationNo).
    // PAID -> EXPIRED로 가는 전이도 없다. 즉 여기 도달한 시점엔 PAID 아니면 CANCELED뿐이다.
    // HOLDING/EXPIRED는 reservationNo가 애초에 없어서 verifyOwnership에서 이미 걸러진다
    private fun requireCancelable(reservation: Reservation) {
        when (reservation.status) {
            ReservationStatus.PAID -> return
            ReservationStatus.CANCELED -> throw ReservationAlreadyCanceledException()
            ReservationStatus.HOLDING, ReservationStatus.EXPIRED ->
                error("reservationNo로 조회됐는데 결제 전 상태입니다(불가능한 상태): id=${reservation.id}, status=${reservation.status}")
        }
    }

    // 정확히 동시에 들어온 취소 요청 중 진 쪽은 여기서 걸림(결제 확정과 같은 패턴)
    private fun confirmCancelAndRefund(reservation: Reservation): Payment =
        try {
            reservation.cancel()
            reservationRepository.save(reservation)
            seatRepository.returnToAvailable(reservation.id)

            val payment =
                requireNotNull(paymentRepository.findByReservationId(reservation.id)) {
                    "PAID 예약인데 결제 기록이 없습니다: ${reservation.id}"
                }
            val fromStatus = payment.status
            payment.markCanceled()
            val canceled = paymentRepository.save(payment)
            historyRecorder.record(canceled.id, fromStatus, PaymentStatus.CANCELED, reason = "사용자 취소 요청")

            applyRefund(canceled)
        } catch (e: OptimisticLockingFailureException) {
            throw PaymentConflictException(cause = e)
        }

    // 환불 실패해도 예외를 던지지 않는다
    private fun applyRefund(payment: Payment): Payment {
        val pgTransactionId = requireNotNull(payment.pgTransactionId) { "취소 대상인데 원 거래 id가 없습니다: ${payment.id}" }
        return when (val result = paymentGateway.refund(pgTransactionId, payment.amount)) {
            is PaymentGatewayResult.Approved -> {
                payment.markRefunded()
                val refunded = paymentRepository.save(payment)
                historyRecorder.record(
                    refunded.id,
                    PaymentStatus.CANCELED,
                    PaymentStatus.REFUNDED,
                    reason = "환불 완료: ${result.pgTransactionId}",
                )
                refunded
            }
            is PaymentGatewayResult.Declined -> payment
        }
    }
}
