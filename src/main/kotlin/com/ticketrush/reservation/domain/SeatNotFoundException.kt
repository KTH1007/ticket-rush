package com.ticketrush.reservation.domain

import com.ticketrush.shared.exception.NotFoundException

class SeatNotFoundException(
    seatId: Long,
) : NotFoundException(code = "SEAT_NOT_FOUND", message = "좌석을 찾을 수 없습니다: $seatId")
