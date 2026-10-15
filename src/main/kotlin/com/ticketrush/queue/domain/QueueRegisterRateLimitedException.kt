package com.ticketrush.queue.domain

import com.ticketrush.shared.exception.TooManyRequestsException
import java.time.Duration

class QueueRegisterRateLimitedException(
    retryAfter: Duration,
) : TooManyRequestsException(code = "QUEUE_REGISTER_RATE_LIMITED", message = "잠시 후 다시 시도해주세요", retryAfter = retryAfter)
