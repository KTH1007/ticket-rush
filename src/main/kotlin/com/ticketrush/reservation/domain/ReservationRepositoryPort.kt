package com.ticketrush.reservation.domain

interface ReservationRepositoryPort {
    fun save(reservation: Reservation): Reservation
}
