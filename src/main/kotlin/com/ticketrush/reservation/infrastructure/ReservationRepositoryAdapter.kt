package com.ticketrush.reservation.infrastructure

import com.ticketrush.reservation.domain.Reservation
import com.ticketrush.reservation.domain.ReservationRepositoryPort
import org.springframework.stereotype.Repository

@Repository
class ReservationRepositoryAdapter(
    private val jpaRepository: ReservationJpaRepository,
) : ReservationRepositoryPort {
    override fun save(reservation: Reservation): Reservation = jpaRepository.saveAndFlush(reservation)
}
