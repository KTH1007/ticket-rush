package com.ticketrush.payment.infrastructure

import com.ticketrush.payment.domain.Payment
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.LocalDateTime

interface PaymentJpaRepository : JpaRepository<Payment, Long> {
    fun findByReservationId(reservationId: Long): Payment?

    @Query(
        "SELECT p FROM Payment p WHERE p.status = com.ticketrush.payment.domain.PaymentStatus.PENDING " +
            "AND p.tossPaymentKey IS NOT NULL AND p.updatedAt < :staleBefore ORDER BY p.updatedAt",
    )
    fun findStalePending(staleBefore: LocalDateTime): List<Payment>
}
