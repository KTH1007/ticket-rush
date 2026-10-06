package com.ticketrush.payment.application

import com.ticketrush.payment.PaymentPolicyProperties
import com.ticketrush.payment.domain.Payment
import com.ticketrush.payment.domain.PaymentRepositoryPort
import com.ticketrush.payment.domain.PaymentStatus
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

private val logger = KotlinLogging.logger {}

// 환불 결과(취소 시점과 재시도 모두)를 반영하는 트랜잭션 단위. 스케줄러의 Toss 호출은 트랜잭션 밖이라 self-invocation을 피하려고 별도 빈으로 둔다
@Component
class PaymentRefundRecorder(
    private val paymentRepository: PaymentRepositoryPort,
    private val historyRecorder: PaymentHistoryRecorder,
    private val policy: PaymentPolicyProperties,
) {
    @Transactional
    fun recordSuccess(
        payment: Payment,
        pgTransactionId: String,
    ) {
        payment.markRefunded()
        val saved = paymentRepository.save(payment)
        historyRecorder.record(saved.id, PaymentStatus.CANCELED, PaymentStatus.REFUNDED, reason = "환불 재시도 성공: $pgTransactionId")
    }

    // 횟수를 올려 저장하면 updatedAt이 갱신돼 refundRetryDelay만큼 다음 시도가 미뤄지고 다음 환불 키도 바뀐다. 저장된 결제를 돌려준다
    @Transactional
    fun recordFailure(
        payment: Payment,
        reason: String,
    ): Payment {
        payment.recordRefundAttempt()
        val saved = paymentRepository.save(payment)
        historyRecorder.record(
            saved.id,
            PaymentStatus.CANCELED,
            PaymentStatus.CANCELED,
            reason = "환불 실패 ${saved.refundAttemptCount}/${policy.refundMaxAttempts}: $reason".take(REASON_MAX_LENGTH),
        )
        // 한도에 도달한 순간 한 번만 알린다. 이후엔 조회에서 빠져 같은 알림이 반복되지 않고, 취소 시점의 실패로 한도에 닿아도 알린다
        if (saved.refundAttemptCount >= policy.refundMaxAttempts) {
            logger.error {
                "환불 시도 한도 초과, 수동 처리 필요: paymentId=${saved.id}, reservationId=${saved.reservationId}, " +
                    "attempts=${saved.refundAttemptCount}"
            }
        }
        return saved
    }

    companion object {
        // payment_history.reason 컬럼 길이. Toss 에러 메시지가 길면 이력 저장이 실패한다
        private const val REASON_MAX_LENGTH = 200
    }
}
