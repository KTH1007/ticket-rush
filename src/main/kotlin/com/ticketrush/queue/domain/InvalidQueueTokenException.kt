package com.ticketrush.queue.domain

import com.ticketrush.shared.exception.UnauthorizedException

class InvalidQueueTokenException :
    UnauthorizedException(
        code = "INVALID_QUEUE_TOKEN",
        message = "유효하지 않거나 만료된 대기열 토큰입니다",
    )
