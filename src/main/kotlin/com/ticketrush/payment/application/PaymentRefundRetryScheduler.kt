package com.ticketrush.payment.application

import com.ticketrush.payment.PaymentPolicyProperties
import com.ticketrush.payment.domain.Payment
import com.ticketrush.payment.domain.PaymentConflictException
import com.ticketrush.payment.domain.PaymentGatewayPort
import com.ticketrush.payment.domain.PaymentGatewayResult
import com.ticketrush.payment.domain.PaymentRepositoryPort
import com.ticketrush.payment.domain.PaymentStatus
import com.ticketrush.payment.domain.RefundIdempotencyKey
import com.ticketrush.reservation.domain.Reservation
import com.ticketrush.reservation.domain.ReservationRepositoryPort
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

// 환불이 안 끝나 CANCELED에 남은 결제를 다시 요청한다. 실패할 때마다 키가 바뀌고, 이미 취소됐다는 응답은 조회로 판정해 중복 환불하지 않는다.
@Component
class PaymentRefundRetryScheduler(
    private val paymentRepository: PaymentRepositoryPort,
    private val reservationRepository: ReservationRepositoryPort,
    private val paymentGateway: PaymentGatewayPort,
    private val recorder: PaymentRefundRecorder,
    private val policy: PaymentPolicyProperties,
    private val clock: Clock,
) {
    // 환불 호출이 길어져 틱이 겹치면 같은 행을 동시에 요청하게 되므로, 한 인스턴스에서는 한 번에 한 틱만 돈다
    private val running = AtomicBoolean(false)

    @Scheduled(fixedRateString = "\${ticket-rush.payment.reclaim-interval}")
    fun retry() {
        retry(LocalDateTime.now(clock).minus(policy.refundRetryDelay))
    }

    @Suppress("TooGenericExceptionCaught")
    fun retry(staleBefore: LocalDateTime) {
        if (!running.compareAndSet(false, true)) return
        try {
            paymentRepository.findStaleCanceled(staleBefore, policy.refundMaxAttempts).forEach { payment ->
                try {
                    retryOne(payment)
                } catch (e: Exception) {
                    logger.warn(e) { "환불 재시도 반영 실패: reservationId=${payment.reservationId}" }
                }
            }
        } finally {
            running.set(false)
        }
    }

    private fun retryOne(snapshot: Payment) {
        val payment = findStillCanceled(snapshot) ?: return
        val reservation =
            requireNotNull(reservationRepository.findById(payment.reservationId)) { "예약을 찾을 수 없습니다: ${payment.reservationId}" }
        val pgTransactionId = requireNotNull(payment.pgTransactionId) { "취소 대상인데 원 거래 id가 없습니다: ${payment.id}" }

        // PG가 같은 환불을 처리 중이면 실패가 아니라서 시도로 세지 않고 다음 틱에 다시 확인한다
        val result = requestRefund(payment, reservation, pgTransactionId) ?: return
        when (result) {
            is PaymentGatewayResult.Approved -> recorder.recordSuccess(payment, result.pgTransactionId)
            is PaymentGatewayResult.Declined -> recorder.recordFailure(payment, result.reason)
        }
    }

    // 목록 조회 뒤 다른 인스턴스나 흐름이 먼저 환불을 끝냈을 수 있어 최신 상태를 다시 읽는다
    private fun findStillCanceled(snapshot: Payment): Payment? =
        paymentRepository.findByReservationId(snapshot.reservationId)?.takeIf { it.status == PaymentStatus.CANCELED }

    // 호출 자체가 실패해도(5xx, 타임아웃, 인증 오류) 환불이 안 된 것으로 보고 거절과 같이 시도 횟수에 센다. 충돌(처리 중)만 null
    @Suppress("TooGenericExceptionCaught")
    private fun requestRefund(
        payment: Payment,
        reservation: Reservation,
        pgTransactionId: String,
    ): PaymentGatewayResult? {
        val refundKey = RefundIdempotencyKey.of(reservation.idempotencyKey, payment.refundAttemptCount)
        return try {
            paymentGateway.refund(pgTransactionId, payment.amount, refundKey)
        } catch (expected: PaymentConflictException) {
            logger.warn { "환불이 PG에서 아직 처리 중입니다. 시도로 세지 않고 다음 틱에 다시 확인합니다: paymentId=${payment.id}" }
            null
        } catch (e: Exception) {
            logger.warn(e) { "환불 재시도 호출 실패: paymentId=${payment.id}" }
            PaymentGatewayResult.Declined(reason = e.message ?: e.javaClass.simpleName)
        }
    }
}
