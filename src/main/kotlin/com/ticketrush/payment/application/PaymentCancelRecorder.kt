package com.ticketrush.payment.application

import com.ticketrush.payment.domain.Payment
import com.ticketrush.payment.domain.PaymentConflictException
import com.ticketrush.payment.domain.PaymentRepositoryPort
import com.ticketrush.payment.domain.PaymentStatus
import com.ticketrush.payment.domain.ReservationCanceledEvent
import com.ticketrush.reservation.domain.Reservation
import com.ticketrush.reservation.domain.ReservationAlreadyCanceledException
import com.ticketrush.reservation.domain.ReservationLookupFailedException
import com.ticketrush.reservation.domain.ReservationLookupRateLimitedException
import com.ticketrush.reservation.domain.ReservationLookupRateLimiterPort
import com.ticketrush.reservation.domain.ReservationRepositoryPort
import com.ticketrush.reservation.domain.ReservationStatus
import com.ticketrush.reservation.domain.SeatRepositoryPort
import com.ticketrush.shared.PhoneHasher
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

// 환불을 요청할 때 필요한 값만 담는다. 환불 키는 예약 키에서 유도한다
data class CanceledPayment(
    val payment: Payment,
    val reservationKey: UUID,
)

// 취소 확정(예약, 좌석, 결제 상태)만 한 트랜잭션으로 커밋한다. Toss 환불 호출은 이 커밋이 끝난 뒤에 별도로 한다
@Suppress("LongParameterList")
@Component
class PaymentCancelRecorder(
    private val reservationRepository: ReservationRepositoryPort,
    private val seatRepository: SeatRepositoryPort,
    private val paymentRepository: PaymentRepositoryPort,
    private val rateLimiter: ReservationLookupRateLimiterPort,
    private val phoneHasher: PhoneHasher,
    private val historyRecorder: PaymentHistoryRecorder,
    private val eventPublisher: ApplicationEventPublisher,
) {
    @Transactional
    fun confirmCancel(
        reservationNo: String,
        phone: String,
    ): CanceledPayment {
        val reservation = verifyOwnership(reservationNo, phone)
        requireCancelable(reservation)
        return CanceledPayment(markCanceled(reservation), reservation.idempotencyKey)
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
    private fun markCanceled(reservation: Reservation): Payment =
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
            eventPublisher.publishEvent(ReservationCanceledEvent(reservation.id))
            canceled
        } catch (e: OptimisticLockingFailureException) {
            throw PaymentConflictException(cause = e)
        }
}
