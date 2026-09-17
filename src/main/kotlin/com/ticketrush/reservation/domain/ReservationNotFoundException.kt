package com.ticketrush.reservation.domain

import com.ticketrush.shared.exception.NotFoundException

class ReservationNotFoundException(
    reservationId: Long,
) : NotFoundException(code = "RESERVATION_NOT_FOUND", message = "예약을 찾을 수 없습니다: $reservationId")
