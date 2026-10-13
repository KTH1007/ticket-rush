package com.ticketrush.reservation.infrastructure

import com.ticketrush.reservation.domain.Seat
import com.ticketrush.reservation.domain.SeatStatus
import org.springframework.data.repository.Repository

interface SeatSoldOutJpaRepository : Repository<Seat, Long> {
    fun existsByEventId(eventId: Long): Boolean

    fun existsByEventIdAndStatusNot(
        eventId: Long,
        status: SeatStatus,
    ): Boolean
}
