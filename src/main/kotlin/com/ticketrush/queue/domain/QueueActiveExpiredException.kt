package com.ticketrush.queue.domain

import com.ticketrush.shared.exception.ForbiddenException

// 아직 순서가 아닌 경우(QUEUE_NOT_ACTIVE)와 구분해 클라이언트가 계속 기다리지 않고 다시 등록하게 한다
class QueueActiveExpiredException :
    ForbiddenException(
        code = "QUEUE_ACTIVE_EXPIRED",
        message = "입장 가능 시간이 끝났습니다. 대기열에 다시 등록해주세요",
    )
