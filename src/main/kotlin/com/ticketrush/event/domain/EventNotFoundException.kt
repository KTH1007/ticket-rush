package com.ticketrush.event.domain

import com.ticketrush.shared.exception.NotFoundException

class EventNotFoundException(
    id: Long,
) : NotFoundException(code = "EVENT_NOT_FOUND", message = "공연을 찾을 수 없습니다: $id")
