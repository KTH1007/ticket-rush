package com.ticketrush.reservation.infrastructure

import com.ticketrush.reservation.domain.Seat
import com.ticketrush.reservation.domain.SeatRepositoryPort
import com.ticketrush.reservation.domain.SeatStatus
import com.ticketrush.shared.PhoneHash
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Repository
class SeatRepositoryAdapter(
    private val jpaRepository: SeatJpaRepository,
) : SeatRepositoryPort {
    override fun save(seat: Seat): Seat = jpaRepository.saveAndFlush(seat)

    override fun findAllByEventId(eventId: Long): List<Seat> = jpaRepository.findAllByEventIdOrderBySectionAscRowLabelAscSeatNoAsc(eventId)

    override fun findHeldOrSoldSlotNos(
        eventId: Long,
        phoneHash: PhoneHash,
    ): List<Short> =
        jpaRepository
            .findAllByEventIdAndPhoneHashAndStatusIn(eventId, phoneHash, listOf(SeatStatus.HELD, SeatStatus.SOLD))
            .mapNotNull { it.slotNo }

    @Transactional
    override fun holdIfAvailable(
        eventId: Long,
        seatId: Long,
        reservationId: Long,
        phoneHash: PhoneHash,
        slotNo: Short,
        holdExpiresAt: LocalDateTime,
    ): Boolean = jpaRepository.holdIfAvailable(seatId, eventId, reservationId, phoneHash, slotNo, holdExpiresAt) > 0

    override fun findAllByIds(seatIds: List<Long>): List<Seat> = jpaRepository.findAllById(seatIds)
}
