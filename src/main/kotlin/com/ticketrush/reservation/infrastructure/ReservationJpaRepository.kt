package com.ticketrush.reservation.infrastructure

import com.ticketrush.reservation.domain.Reservation
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface ReservationJpaRepository : JpaRepository<Reservation, Long> {
    fun existsByReservationNo(reservationNo: String): Boolean

    @Modifying(clearAutomatically = true)
    @Query(
        """
        UPDATE Reservation r SET r.status = com.ticketrush.reservation.domain.ReservationStatus.EXPIRED,
            r.version = r.version + 1
        WHERE r.status = com.ticketrush.reservation.domain.ReservationStatus.HOLDING AND r.holdExpiresAt < :now
        """,
    )
    fun expireHoldingReservations(
        @Param("now") now: LocalDateTime,
    ): Int
}
