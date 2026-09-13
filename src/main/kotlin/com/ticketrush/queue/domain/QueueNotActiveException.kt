package com.ticketrush.queue.domain

import com.ticketrush.shared.exception.ForbiddenException

class QueueNotActiveException :
    ForbiddenException(
        code = "QUEUE_NOT_ACTIVE",
        message = "아직 입장 순서가 되지 않았습니다",
    )
