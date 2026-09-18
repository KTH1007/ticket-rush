package com.ticketrush.payment.infrastructure

import com.ticketrush.payment.domain.PaymentHistory
import org.springframework.data.jpa.repository.JpaRepository

interface PaymentHistoryJpaRepository : JpaRepository<PaymentHistory, Long> {
    fun findAllByPaymentId(paymentId: Long): List<PaymentHistory>
}
