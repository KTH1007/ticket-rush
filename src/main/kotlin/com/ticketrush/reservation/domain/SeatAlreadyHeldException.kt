package com.ticketrush.reservation.domain

import com.ticketrush.shared.exception.ConflictException

class SeatAlreadyHeldException :
    ConflictException(
        code = "SEAT_ALREADY_HELD",
        message = "이미 선점되었거나 판매된 좌석이 포함되어 있습니다",
    )
