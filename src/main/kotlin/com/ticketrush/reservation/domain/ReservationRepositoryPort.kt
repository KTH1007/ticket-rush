package com.ticketrush.reservation.domain

import java.time.LocalDateTime

interface ReservationRepositoryPort {
    fun save(reservation: Reservation): Reservation

    fun findById(id: Long): Reservation?

    fun findByReservationNo(reservationNo: String): Reservation?

    // 예매번호 재시도용 사전 체크.
    fun existsByReservationNo(reservationNo: String): Boolean

    fun expireHoldingReservations(now: LocalDateTime): Int
}
