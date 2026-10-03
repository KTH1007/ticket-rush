package com.ticketrush.payment.application

import com.ticketrush.payment.PaymentPolicyProperties
import com.ticketrush.payment.domain.Payment
import com.ticketrush.payment.domain.PaymentGatewayPort
import com.ticketrush.payment.domain.PaymentGatewayResult
import com.ticketrush.payment.domain.PaymentRepositoryPort
import com.ticketrush.reservation.domain.Reservation
import com.ticketrush.reservation.domain.ReservationRepositoryPort
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

// OutboxReclaimScheduler와 같은 모양: stale PENDING을 찾아 같은 정보로 PG를 다시 부른다.
// idempotencyKey가 같으므로 Toss가 안전하게 같은 결과를 돌려준다.
@Component
class PaymentReclaimScheduler(
    private val paymentRepository: PaymentRepositoryPort,
    private val reservationRepository: ReservationRepositoryPort,
    private val paymentGateway: PaymentGatewayPort,
    private val claimService: PaymentClaimService,
    private val policy: PaymentPolicyProperties,
    private val clock: Clock,
) {
    // 같은 행이 매 틱 ERROR를 쏟아내지 않도록 인스턴스당 한 번만 남긴다
    private val alertedPaymentIds = ConcurrentHashMap.newKeySet<Long>()

    @Scheduled(fixedRateString = "\${ticket-rush.payment.reclaim-interval}")
    fun reclaim() {
        reclaim(LocalDateTime.now(clock).minus(policy.staleClaimTimeout))
    }

    @Suppress("TooGenericExceptionCaught")
    fun reclaim(staleBefore: LocalDateTime) {
        paymentRepository.findStalePending(staleBefore).forEach { payment ->
            try {
                reclaimOne(payment)
            } catch (e: Exception) {
                logger.warn(e) { "결제 재시도 실패: reservationId=${payment.reservationId}" }
            }
        }
    }

    private fun reclaimOne(payment: Payment) {
        val paymentKey = payment.tossPaymentKey
        val orderId = payment.tossOrderId
        if (paymentKey == null || orderId == null) {
            logger.warn { "재시도에 필요한 paymentKey/orderId가 없어 건너뜁니다: paymentId=${payment.id}" }
            return
        }
        val reservation =
            requireNotNull(reservationRepository.findById(payment.reservationId)) { "예약을 찾을 수 없습니다: ${payment.reservationId}" }
        val now = LocalDateTime.now(clock)

        // HoldExpiryScheduler가 PENDING 결제를 보지 않고 예약을 만료시킨다. 늦은 승인이 와도 반영할 수 없으니 PG를 부르지 않는다
        if (reservation.isHoldActiveAt(now)) {
            retryCharge(payment, reservation, paymentKey, orderId, now)
        } else {
            alertOnce(payment, reservation)
        }
    }

    private fun retryCharge(
        payment: Payment,
        reservation: Reservation,
        paymentKey: String,
        orderId: String,
        now: LocalDateTime,
    ) {
        val claimed = claimService.claimOrTakeOver(reservation.id, payment.amount, paymentKey, orderId, now)
        when (val result = paymentGateway.charge(paymentKey, orderId, payment.amount, reservation.idempotencyKey)) {
            is PaymentGatewayResult.Approved ->
                claimService.applySuccess(claimed, reservation, result.pgTransactionId, result.approvedAt ?: now)
            is PaymentGatewayResult.Declined -> claimService.applyFailure(claimed, reservation, now, result.reason)
        }
    }

    private fun alertOnce(
        payment: Payment,
        reservation: Reservation,
    ) {
        if (!alertedPaymentIds.add(payment.id)) return
        logger.error {
            "승인 여부를 알 수 없는 PENDING 결제, 대사 필요: " +
                "reservationId=${reservation.id}, paymentId=${payment.id}, reservationStatus=${reservation.status}"
        }
    }
}
