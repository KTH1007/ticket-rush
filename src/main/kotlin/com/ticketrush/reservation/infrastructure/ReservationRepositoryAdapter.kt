package com.ticketrush.reservation.infrastructure

import com.ticketrush.reservation.domain.Reservation
import com.ticketrush.reservation.domain.ReservationRepositoryPort
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Repository
class ReservationRepositoryAdapter(
    private val jpaRepository: ReservationJpaRepository,
) : ReservationRepositoryPort {
    override fun save(reservation: Reservation): Reservation = jpaRepository.saveAndFlush(reservation)

    @Transactional
    override fun expireHoldingReservations(now: LocalDateTime): Int = jpaRepository.expireHoldingReservations(now)
}
