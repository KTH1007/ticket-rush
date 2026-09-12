package com.ticketrush.reservation.infrastructure

import com.ticketrush.reservation.domain.Reservation
import org.springframework.data.jpa.repository.JpaRepository

interface ReservationJpaRepository : JpaRepository<Reservation, Long>
