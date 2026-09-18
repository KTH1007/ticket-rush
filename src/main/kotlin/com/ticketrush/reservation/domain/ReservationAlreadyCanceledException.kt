package com.ticketrush.reservation.domain

import com.ticketrush.shared.exception.ConflictException

class ReservationAlreadyCanceledException :
    ConflictException(
        code = "RESERVATION_ALREADY_CANCELED",
        message = "이미 취소된 예약입니다",
    )
