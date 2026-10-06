package com.ticketrush.payment.application

import com.ticketrush.payment.domain.ChargeIdempotencyKey
import com.ticketrush.payment.domain.Payment
import com.ticketrush.payment.domain.PaymentConfirmationResult
import com.ticketrush.payment.domain.PaymentConflictException
import com.ticketrush.payment.domain.PaymentDeclinedException
import com.ticketrush.payment.domain.PaymentGatewayPort
import com.ticketrush.payment.domain.PaymentGatewayResult
import com.ticketrush.payment.domain.PaymentOrderMismatchException
import com.ticketrush.payment.domain.PaymentRepositoryPort
import com.ticketrush.payment.domain.PaymentStatus
import com.ticketrush.reservation.domain.HoldTokenMismatchException
import com.ticketrush.reservation.domain.Reservation
import com.ticketrush.reservation.domain.ReservationNotFoundException
import com.ticketrush.reservation.domain.ReservationNotHoldingException
import com.ticketrush.reservation.domain.ReservationRepositoryPort
import com.ticketrush.reservation.domain.ReservationStatus
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDateTime
import java.util.UUID

@Service
class PaymentCommandService(
    private val reservationRepository: ReservationRepositoryPort,
    private val paymentRepository: PaymentRepositoryPort,
    private val paymentGateway: PaymentGatewayPort,
    private val claimService: PaymentClaimService,
    private val clock: Clock,
) {
    // 더 이상 @Transactional이 아니다. PG 호출이 트랜잭션 밖에서 일어나야 하므로 이 메서드
    // 자체를 트랜잭션으로 감쌀 수 없다(claimService의 각 단계가 자기 트랜잭션을 갖는다).
    fun confirmPayment(
        reservationId: Long,
        holdToken: UUID,
        paymentKey: String,
        orderId: String,
        amount: Int,
    ): PaymentConfirmationResult {
        val reservation = loadOwnedReservation(reservationId, holdToken)

        val existingPayment = paymentRepository.findByReservationId(reservationId)
        successResultOrNull(reservation, existingPayment)?.let { return it }

        requireHolding(reservation)
        requireOrderMatches(reservation, orderId, amount)

        val claimed = claim(reservation, paymentKey, orderId)
        return callGatewayAndApply(reservation, claimed, paymentKey, orderId, amount)
    }

    // 같은 결제를 동시에 이어받거나 다시 열다 진 쪽은 낙관적 락 충돌로 걸리는데, 서버 오류가 아니라 충돌로 응답한다
    private fun claim(
        reservation: Reservation,
        paymentKey: String,
        orderId: String,
    ): Payment =
        try {
            claimService.claimOrTakeOver(reservation.id, reservation.amount, paymentKey, orderId, LocalDateTime.now(clock))
        } catch (e: OptimisticLockingFailureException) {
            throw PaymentConflictException(cause = e)
        }

    // 만료된 클레임을 동시에 넘겨받은 두 요청 중 진 쪽은 결과 반영 저장에서 낙관적 락 충돌로 걸린다
    private fun callGatewayAndApply(
        reservation: Reservation,
        claimed: Payment,
        paymentKey: String,
        orderId: String,
        amount: Int,
    ): PaymentConfirmationResult =
        try {
            // Toss가 같은 키의 거절을 재생하므로 거절된 적이 있으면 그 횟수를 섞은 새 키로 요청한다
            val chargeKey = ChargeIdempotencyKey.of(reservation.idempotencyKey, claimed.chargeAttemptCount)
            when (val result = paymentGateway.charge(paymentKey, orderId, amount, chargeKey)) {
                is PaymentGatewayResult.Approved ->
                    claimService.applySuccess(
                        claimed,
                        reservation,
                        result.pgTransactionId,
                        // PG 승인 시각이 정산 기준이라 우선하고, 없을 때만 서버 시각으로 대체한다
                        result.approvedAt ?: LocalDateTime.now(clock),
                    )
                is PaymentGatewayResult.Declined -> {
                    claimService.applyFailure(claimed, reservation, LocalDateTime.now(clock), result.reason)
                    throw PaymentDeclinedException(result.reason)
                }
            }
        } catch (e: OptimisticLockingFailureException) {
            throw PaymentConflictException(cause = e)
        }

    private fun requireHolding(reservation: Reservation) {
        if (reservation.status != ReservationStatus.HOLDING) throw ReservationNotHoldingException(reservation.status)
        if (!reservation.isHoldActiveAt(LocalDateTime.now(clock))) throw ReservationNotHoldingException(ReservationStatus.EXPIRED)
    }

    private fun requireOrderMatches(
        reservation: Reservation,
        orderId: String,
        amount: Int,
    ) {
        if (reservation.idempotencyKey.toString() != orderId) throw PaymentOrderMismatchException()
        if (reservation.amount != amount) throw PaymentOrderMismatchException()
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
}
