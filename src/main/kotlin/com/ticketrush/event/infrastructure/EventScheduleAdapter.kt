package com.ticketrush.event.infrastructure

import com.ticketrush.event.domain.EventQueryPort
import com.ticketrush.queue.domain.EventSchedulePort
import org.springframework.stereotype.Component
import java.time.LocalDateTime

// 대기열 모듈이 정의한 포트의 구현. 의존 방향을 event -> queue로 두어 모듈 순환을 피한다
@Component
class EventScheduleAdapter(
    private val eventQueryPort: EventQueryPort,
) : EventSchedulePort {
    override fun opensAt(eventId: Long): LocalDateTime? = eventQueryPort.findById(eventId)?.opensAt
}
