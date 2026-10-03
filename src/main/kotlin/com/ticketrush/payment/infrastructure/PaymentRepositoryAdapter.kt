package com.ticketrush.payment.infrastructure

import com.ticketrush.payment.domain.Payment
import com.ticketrush.payment.domain.PaymentRepositoryPort
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
class PaymentRepositoryAdapter(
    private val jpaRepository: PaymentJpaRepository,
) : PaymentRepositoryPort {
    override fun save(payment: Payment): Payment = jpaRepository.saveAndFlush(payment)

    override fun findByReservationId(reservationId: Long): Payment? = jpaRepository.findByReservationId(reservationId)

    override fun findStalePending(staleBefore: LocalDateTime): List<Payment> = jpaRepository.findStalePending(staleBefore)
}
