package com.ticketrush.payment.application

import com.ticketrush.payment.PaymentPolicyProperties
import com.ticketrush.payment.domain.Payment
import com.ticketrush.payment.domain.PaymentGatewayResult
import com.ticketrush.payment.domain.PaymentRepositoryPort
import com.ticketrush.payment.domain.PaymentStatus
import com.ticketrush.reservation.domain.ReservationRepositoryPort
import com.ticketrush.shared.TickGuard
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDateTime

private val logger = KotlinLogging.logger {}

// 환불이 안 끝나 CANCELED에 남은 결제를 다시 요청한다. 실패할 때마다 키가 바뀌고, 이미 취소됐다는 응답은 조회로 판정해 중복 환불하지 않는다.
@Component
class PaymentRefundRetryScheduler(
    private val paymentRepository: PaymentRepositoryPort,
    private val reservationRepository: ReservationRepositoryPort,
    private val refunder: PaymentRefunder,
    private val recorder: PaymentRefundRecorder,
    private val policy: PaymentPolicyProperties,
    private val clock: Clock,
) {
    // 환불 호출이 길어져 틱이 겹치면 같은 행을 동시에 요청하게 되므로, 한 인스턴스에서는 한 번에 한 틱만 돈다
    private val tick = TickGuard()

    // 기동 직후 즉시 도는 첫 틱을 한 주기 미룬다. 첫 틱이 돌고 있으면 겹침 방지 가드 때문에 같은 시점에 직접 부른 호출이 건너뛰어진다
    @Scheduled(
        fixedRateString = "\${ticket-rush.payment.refund-retry-interval}",
        initialDelayString = "\${ticket-rush.payment.refund-retry-interval}",
    )
    fun retry() {
        retry(LocalDateTime.now(clock).minus(policy.refundRetryDelay))
    }

    fun retry(staleBefore: LocalDateTime) =
        tick.runIfIdle {
            for (payment in paymentRepository.findStaleCanceled(staleBefore, policy.refundMaxAttempts)) {
                if (!retrySafely(payment)) break
            }
        }

    // 다른 인스턴스와 낙관적 락으로 겹치는 건 정상 경합이라 스택트레이스 없이 한 줄만 남긴다.
    // false면 이번 틱을 멈춘다
    @Suppress("TooGenericExceptionCaught")
    private fun retrySafely(payment: Payment): Boolean {
        try {
            return retryOne(payment)
        } catch (expected: OptimisticLockingFailureException) {
            logger.warn { "다른 인스턴스가 먼저 반영해 건너뜁니다: reservationId=${payment.reservationId}" }
        } catch (e: Exception) {
            logger.warn(e) { "환불 재시도 반영 실패: reservationId=${payment.reservationId}" }
        }
        return true
    }

    // 틱을 계속할지 돌려준다. PG에 닿지 않았으면 나머지 행도 같은 이유로 실패하므로 멈춘다
    private fun retryOne(snapshot: Payment): Boolean {
        val payment = findStillCanceled(snapshot) ?: return true
        val reservation =
            requireNotNull(reservationRepository.findById(payment.reservationId)) { "예약을 찾을 수 없습니다: ${payment.reservationId}" }

        val result =
            when (val outcome = refunder.refund(payment, reservation.idempotencyKey)) {
                is PaymentRefunder.Outcome.Completed -> outcome.result
                // 실패가 아니라서 시도로 세지 않고 다음 틱에 다시 확인한다
                PaymentRefunder.Outcome.InProgress -> return true
                PaymentRefunder.Outcome.Unreachable -> {
                    logger.warn { "PG에 닿지 않아 이번 틱의 환불 재시도를 멈춥니다" }
                    return false
                }
            }
        when (result) {
            is PaymentGatewayResult.Approved -> recorder.recordSuccess(payment, result)
            is PaymentGatewayResult.Declined -> recorder.recordFailure(payment, result.reason)
        }
        return true
    }

    // 목록 조회 뒤 다른 인스턴스나 흐름이 먼저 환불을 끝냈을 수 있어 최신 상태를 다시 읽는다
    private fun findStillCanceled(snapshot: Payment): Payment? =
        paymentRepository.findByReservationId(snapshot.reservationId)?.takeIf { it.status == PaymentStatus.CANCELED }
}
