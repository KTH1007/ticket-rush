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
                reason = reason,
                createdAt = LocalDateTime.now(clock),
            ),
        )
    }
}
