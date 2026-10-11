package com.ticketrush.reservation.infrastructure

import com.ticketrush.reservation.domain.SeatLayoutRow
import com.ticketrush.reservation.domain.SeatMapQueryPort
import com.ticketrush.reservation.domain.SeatStatusRow
import org.springframework.stereotype.Repository

@Repository
class SeatMapQueryAdapter(
    private val jpaRepository: SeatJpaRepository,
) : SeatMapQueryPort {
    override fun findLayoutRowsByEventId(eventId: Long): List<SeatLayoutRow> = jpaRepository.findLayoutRowsByEventId(eventId)

    override fun findStatusRowsByEventId(eventId: Long): List<SeatStatusRow> = jpaRepository.findStatusRowsByEventId(eventId)
}
