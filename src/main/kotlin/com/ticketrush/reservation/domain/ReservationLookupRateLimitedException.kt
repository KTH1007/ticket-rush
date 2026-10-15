package com.ticketrush.reservation.domain

import com.ticketrush.shared.exception.TooManyRequestsException
import java.time.Duration

class ReservationLookupRateLimitedException(
    retryAfter: Duration? = null,
) : TooManyRequestsException(
        code = "RESERVATION_LOOKUP_RATE_LIMITED",
        message = "잠시 후 다시 시도해주세요",
        retryAfter = retryAfter,
    )
