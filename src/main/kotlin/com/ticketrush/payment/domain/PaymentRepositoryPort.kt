package com.ticketrush.payment.domain

interface PaymentRepositoryPort {
    fun save(payment: Payment): Payment

    fun findByReservationId(reservationId: Long): Payment?
}
