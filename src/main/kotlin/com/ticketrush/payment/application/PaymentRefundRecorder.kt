package com.ticketrush.payment.application

import com.ticketrush.payment.domain.Payment
import com.ticketrush.payment.domain.PaymentRepositoryPort
import com.ticketrush.payment.domain.PaymentStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

// 환불 재시도 결과를 반영하는 트랜잭션 단위. Toss 호출은 트랜잭션 밖이라, self-invocation을 피하려고 별도 빈으로 둔다
@Component
class PaymentRefundRecorder(
    private val paymentRepository: PaymentRepositoryPort,
    private val historyRecorder: PaymentHistoryRecorder,
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

    // 횟수를 올려 저장하면 updatedAt이 갱신돼 refundRetryDelay만큼 다음 시도가 미뤄진다. 한도 판단을 위해 저장된 결제를 돌려준다
    @Transactional
    fun recordFailure(
        payment: Payment,
        reason: String,
        maxAttempts: Int,
    ): Payment {
        payment.recordRefundAttempt()
        val saved = paymentRepository.save(payment)
        historyRecorder.record(
            saved.id,
            PaymentStatus.CANCELED,
            PaymentStatus.CANCELED,
            reason = "환불 재시도 실패 ${saved.refundAttemptCount}/$maxAttempts: $reason".take(REASON_MAX_LENGTH),
        )
        return saved
    }

    companion object {
        // payment_history.reason 컬럼 길이. Toss 에러 메시지가 길면 이력 저장이 실패한다
        private const val REASON_MAX_LENGTH = 200
    }
}
