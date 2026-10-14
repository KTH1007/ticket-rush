package com.ticketrush.queue.domain

import com.ticketrush.shared.exception.ForbiddenException

class QueuePhoneMismatchException :
    ForbiddenException(
        code = "QUEUE_PHONE_MISMATCH",
        message = "이 대기열 토큰은 다른 전화번호로 이미 사용됐습니다",
    )
