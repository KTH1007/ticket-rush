package com.ticketrush.reservation.infrastructure

import com.ticketrush.reservation.domain.Seat
import com.ticketrush.reservation.domain.SeatStatus
import com.ticketrush.shared.PhoneHash
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface SeatJpaRepository : JpaRepository<Seat, Long> {
    fun findAllByEventIdOrderBySectionAscRowLabelAscSeatNoAsc(eventId: Long): List<Seat>

    fun findAllByEventIdAndPhoneHashAndStatusIn(
        eventId: Long,
        phoneHash: PhoneHash,
        statuses: List<SeatStatus>,
    ): List<Seat>

    @Modifying(clearAutomatically = true)
    @Query(
        """
        UPDATE Seat s SET s.status = com.ticketrush.reservation.domain.SeatStatus.HELD,
            s.reservationId = :reservationId, s.phoneHash = :phoneHash, s.slotNo = :slotNo, s.holdExpiresAt = :holdExpiresAt
        WHERE s.id = :seatId AND s.eventId = :eventId AND s.status = com.ticketrush.reservation.domain.SeatStatus.AVAILABLE
        """,
    )
    fun holdIfAvailable(
        @Param("seatId") seatId: Long,
        @Param("eventId") eventId: Long,
        @Param("reservationId") reservationId: Long,
        @Param("phoneHash") phoneHash: PhoneHash,
        @Param("slotNo") slotNo: Short,
        @Param("holdExpiresAt") holdExpiresAt: LocalDateTime,
    ): Int
}
