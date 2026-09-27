package com.ticketrush.payment.application

import com.ticketrush.payment.PaymentPolicyProperties
import com.ticketrush.payment.domain.Payment
import com.ticketrush.payment.domain.PaymentConflictException
import com.ticketrush.payment.domain.PaymentRepositoryPort
import com.ticketrush.payment.domain.PaymentStatus
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

// claim(트랜잭션 1)과 결과 반영(트랜잭션 2)을 PaymentCommandService와 분리된 빈으로 둔다.
@Component
class PaymentClaimService(
    private val paymentRepository: PaymentRepositoryPort,
    private val policy: PaymentPolicyProperties,
) {
    @Transactional
    fun claimOrTakeOver(
        reservationId: Long,
        amount: Int,
        now: LocalDateTime,
    ): Payment {
        val existing = paymentRepository.findByReservationId(reservationId)
        if (existing != null) return takeOverIfStale(existing, now)
        return insertNewClaim(reservationId, amount)
    }

    private fun takeOverIfStale(
        existing: Payment,
        now: LocalDateTime,
    ): Payment {
        if (existing.status != PaymentStatus.PENDING) return existing
        val claimedAt = requireNotNull(existing.updatedAt) { "저장된 Payment는 updatedAt이 있어야 합니다: ${existing.id}" }
        if (claimedAt.plus(policy.staleClaimTimeout).isAfter(now)) throw PaymentConflictException()
        return existing
    }

    private fun insertNewClaim(
        reservationId: Long,
        amount: Int,
    ): Payment =
        try {
            paymentRepository.save(Payment(reservationId = reservationId, amount = amount))
        } catch (e: DataIntegrityViolationException) {
            if (e.message?.contains("uk_payment_reservation") == true) throw PaymentConflictException(cause = e)
            throw e
        }
}
