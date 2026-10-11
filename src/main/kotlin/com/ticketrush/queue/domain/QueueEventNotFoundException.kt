package com.ticketrush.queue.domain

import com.ticketrush.shared.exception.NotFoundException

// 이벤트 모듈의 EventNotFoundException과 같은 코드와 상태로 응답해 클라이언트가 두 경우를 구분하지 않게 한다
class QueueEventNotFoundException(
    eventId: Long,
) : NotFoundException(code = "EVENT_NOT_FOUND", message = "공연을 찾을 수 없습니다: $eventId")
