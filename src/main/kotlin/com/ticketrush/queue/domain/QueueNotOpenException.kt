package com.ticketrush.queue.domain

import com.ticketrush.shared.exception.ConflictException
import java.time.LocalDateTime

// 공연은 있지만 아직 오픈 전이라 줄을 세우지 않는 경우. 없는 공연(404)과 구분해 클라이언트가 오픈 시각을 안내할 수 있게 409로 응답한다
class QueueNotOpenException(
    eventId: Long,
    opensAt: LocalDateTime,
) : ConflictException(code = "QUEUE_NOT_OPEN", message = "아직 대기열을 받지 않습니다: eventId=$eventId, 오픈 시각=$opensAt")
