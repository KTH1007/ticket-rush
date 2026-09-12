package com.ticketrush.reservation.domain

import com.ticketrush.shared.exception.ConflictException

class ReservationLimitExceededException :
    ConflictException(
        code = "RESERVATION_LIMIT_EXCEEDED",
        message = "1인당 최대 2매까지만 예매할 수 있습니다",
    )
