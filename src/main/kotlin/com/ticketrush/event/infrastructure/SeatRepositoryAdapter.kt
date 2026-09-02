package com.ticketrush.event.infrastructure

import com.ticketrush.event.domain.Seat
import com.ticketrush.event.domain.SeatRepositoryPort
import org.springframework.stereotype.Repository

@Repository
class SeatRepositoryAdapter(
    private val jpaRepository: SeatJpaRepository,
) : SeatRepositoryPort {
    override fun save(seat: Seat): Seat = jpaRepository.saveAndFlush(seat)

    override fun findAllByEventId(eventId: Long): List<Seat> = jpaRepository.findAllByEventIdOrderBySectionAscRowLabelAscSeatNoAsc(eventId)
}
