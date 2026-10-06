package com.ticketrush.payment.application

import com.ticketrush.payment.PaymentPolicyProperties
import com.ticketrush.payment.domain.ChargeIdempotencyKey
import com.ticketrush.payment.domain.Payment
import com.ticketrush.payment.domain.PaymentGatewayPort
import com.ticketrush.payment.domain.PaymentGatewayResult
import com.ticketrush.payment.domain.PaymentInquiryResult
import com.ticketrush.payment.domain.PaymentRepositoryPort
import com.ticketrush.payment.domain.PaymentStatus
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
// 키는 거절된 횟수를 섞은 값이라 같은 시도의 재요청에는 Toss가 같은 결과를 돌려주고, 거절 뒤 새 시도는 새 키를 쓴다.
@Component
class PaymentReclaimScheduler(
    private val paymentRepository: PaymentRepositoryPort,
    private val reservationRepository: ReservationRepositoryPort,
    private val paymentGateway: PaymentGatewayPort,
    private val claimService: PaymentClaimService,
    private val policy: PaymentPolicyProperties,
    private val clock: Clock,
) {
    // Toss가 승인했는데 예약이 만료된 결제. 매 틱 Toss를 다시 부르거나 ERROR를 쏟아내지 않도록 인스턴스당 한 번만 처리한다
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

    private fun reclaimOne(snapshot: Payment) {
        val payment = findStillPending(snapshot) ?: return
        val paymentKey = payment.tossPaymentKey
        val orderId = payment.tossOrderId
        if (paymentKey == null || orderId == null) {
            logger.warn { "재시도에 필요한 paymentKey/orderId가 없어 건너뜁니다: paymentId=${payment.id}" }
            return
        }
        val reservation =
            requireNotNull(reservationRepository.findById(payment.reservationId)) { "예약을 찾을 수 없습니다: ${payment.reservationId}" }
        val now = LocalDateTime.now(clock)

        // HoldExpiryScheduler가 PENDING 결제를 보지 않고 예약을 만료시킨다. 늦은 승인이 와도 반영할 수 없으니 승인 요청은 하지 않는다
        if (reservation.isHoldActiveAt(now)) {
            retryCharge(payment, reservation, paymentKey, orderId, now)
        } else {
            reconcileByInquiry(payment, reservation, paymentKey)
        }
    }

    // 목록 조회 뒤 PG 호출을 거치는 사이 다른 인스턴스나 사용자 재시도가 먼저 확정했을 수 있어 최신 상태를 다시 읽는다
    private fun findStillPending(snapshot: Payment): Payment? =
        paymentRepository.findByReservationId(snapshot.reservationId)?.takeIf { it.status == PaymentStatus.PENDING }

    private fun retryCharge(
        payment: Payment,
        reservation: Reservation,
        paymentKey: String,
        orderId: String,
        now: LocalDateTime,
    ) {
        val claimed = claimService.claimOrTakeOver(reservation.id, payment.amount, paymentKey, orderId, now)
        // 재확인 직후 확정됐다면 claimOrTakeOver가 그 행을 그대로 돌려주므로 다시 승인하지 않는다
        if (claimed.status != PaymentStatus.PENDING) return
        val chargeKey = ChargeIdempotencyKey.of(reservation.idempotencyKey, claimed.chargeAttemptCount)
        when (val result = paymentGateway.charge(paymentKey, orderId, payment.amount, chargeKey)) {
            is PaymentGatewayResult.Approved ->
                claimService.applySuccess(claimed, reservation, result.pgTransactionId, result.approvedAt ?: now)
            is PaymentGatewayResult.Declined -> claimService.applyFailure(claimed, reservation, now, result.reason)
        }
    }

    // 승인 요청 대신 조회로 실제 승인 여부만 확인한다. 조회가 예외면 PENDING으로 두고 다음 틱에 다시 확인한다
    private fun reconcileByInquiry(
        payment: Payment,
        reservation: Reservation,
        paymentKey: String,
    ) {
        if (payment.id in alertedPaymentIds) return
        when (val inquiry = paymentGateway.inquire(paymentKey)) {
            is PaymentInquiryResult.Done -> alertRefundNeeded(payment, reservation, inquiry)
            is PaymentInquiryResult.NotApproved ->
                claimService.markFailedByReconciliation(payment, "Toss 미승인(${inquiry.status}), 예약을 쓸 수 없어 결제 실패로 확정")
            PaymentInquiryResult.NotFound ->
                claimService.markFailedByReconciliation(payment, "Toss에 결제 내역 없음, 예약을 쓸 수 없어 결제 실패로 확정")
        }
    }

    // 돈이 움직이는 판단이라 자동 환불하지 않고 PENDING 그대로 둔 채 사람이 처리하도록 알린다
    private fun alertRefundNeeded(
        payment: Payment,
        reservation: Reservation,
        inquiry: PaymentInquiryResult.Done,
    ) {
        alertedPaymentIds.add(payment.id)
        logger.error {
            "Toss는 승인(DONE)했지만 예약이 만료됨, 환불 필요: reservationId=${reservation.id}, paymentId=${payment.id}, " +
                "reservationStatus=${reservation.status}, approvedAt=${inquiry.approvedAt}"
        }
    }
}
