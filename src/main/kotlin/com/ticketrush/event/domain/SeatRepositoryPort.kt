package com.ticketrush.event.domain

interface SeatRepositoryPort {
    fun save(seat: Seat): Seat

    fun findAllByEventId(eventId: Long): List<Seat>
}
