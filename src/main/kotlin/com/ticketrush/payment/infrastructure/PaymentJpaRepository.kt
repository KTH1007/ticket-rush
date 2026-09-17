package com.ticketrush.payment.infrastructure

import com.ticketrush.payment.domain.Payment
import org.springframework.data.jpa.repository.JpaRepository

interface PaymentJpaRepository : JpaRepository<Payment, Long> {
    fun findByReservationId(reservationId: Long): Payment?
}
