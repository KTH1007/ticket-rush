package com.ticketrush.event.infrastructure

import com.ticketrush.event.domain.Seat
import org.springframework.data.jpa.repository.JpaRepository

interface SeatJpaRepository : JpaRepository<Seat, Long> {
    fun findAllByEventIdOrderBySectionAscRowLabelAscSeatNoAsc(eventId: Long): List<Seat>
}
