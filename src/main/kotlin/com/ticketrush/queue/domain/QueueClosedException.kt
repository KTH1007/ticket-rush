package com.ticketrush.queue.domain

import com.ticketrush.shared.exception.ConflictException
import java.time.LocalDateTime

// 공연이 이미 시작해 줄을 세우지 않는 경우. 오픈 전(QUEUE_NOT_OPEN)과 구분해 클라이언트가 "다시 오면 된다"고 안내하지 않게 한다
class QueueClosedException(
    eventId: Long,
    startsAt: LocalDateTime,
) : ConflictException(code = "QUEUE_CLOSED", message = "이미 시작한 공연은 대기열을 받지 않습니다: eventId=$eventId, 시작 시각=$startsAt")
