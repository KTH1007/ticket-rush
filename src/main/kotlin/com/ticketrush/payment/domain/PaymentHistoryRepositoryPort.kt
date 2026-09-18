package com.ticketrush.payment.domain

interface PaymentHistoryRepositoryPort {
    fun save(history: PaymentHistory): PaymentHistory

    fun findAllByPaymentId(paymentId: Long): List<PaymentHistory>
}
