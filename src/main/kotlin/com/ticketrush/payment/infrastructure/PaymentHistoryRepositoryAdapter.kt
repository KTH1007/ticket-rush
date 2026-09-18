package com.ticketrush.payment.infrastructure

import com.ticketrush.payment.domain.PaymentHistory
import com.ticketrush.payment.domain.PaymentHistoryRepositoryPort
import org.springframework.stereotype.Repository

@Repository
class PaymentHistoryRepositoryAdapter(
    private val jpaRepository: PaymentHistoryJpaRepository,
) : PaymentHistoryRepositoryPort {
    override fun save(history: PaymentHistory): PaymentHistory = jpaRepository.saveAndFlush(history)

    override fun findAllByPaymentId(paymentId: Long): List<PaymentHistory> = jpaRepository.findAllByPaymentId(paymentId)
}
