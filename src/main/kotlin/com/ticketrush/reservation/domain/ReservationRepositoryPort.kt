package com.ticketrush.reservation.domain

import java.time.LocalDateTime

interface ReservationRepositoryPort {
    fun save(reservation: Reservation): Reservation

    fun expireHoldingReservations(now: LocalDateTime): Int
}
