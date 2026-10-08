package com.ticketrush.payment.application

import com.ticketrush.payment.domain.PaymentHistory
import com.ticketrush.payment.domain.PaymentHistoryRepositoryPort
import com.ticketrush.payment.domain.PaymentStatus
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDateTime

@Component
class PaymentHistoryRecorder(
    private val paymentHistoryRepository: PaymentHistoryRepositoryPort,
    private val clock: Clock,
) {
    fun record(
        paymentId: Long,
        from: PaymentStatus?,
        to: PaymentStatus,
        reason: String? = null,
    ) {
        paymentHistoryRepository.save(
            PaymentHistory(
                paymentId = paymentId,
                fromStatus = from,
                toStatus = to,
                reason = reason?.take(REASON_MAX_LENGTH),
                createdAt = LocalDateTime.now(clock),
            ),
        )
    }

    companion object {
        // payment_history.reason 컬럼 길이. 넘기면 이력 저장이 실패하고 같은 트랜잭션의 결제 반영도 롤백된다
        private const val REASON_MAX_LENGTH = 200
    }
}
