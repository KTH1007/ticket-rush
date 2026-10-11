package com.ticketrush.queue.domain

import java.time.Instant

// 대기열이 이벤트 모듈을 직접 의존하면 event -> reservation -> queue -> event 순환이 생겨서, 필요한 것을 포트로 정의하고 이벤트 모듈이 구현한다
interface EventSchedulePort {
    // 판매 오픈 시각. 이벤트가 없으면 null
    fun opensAt(eventId: Long): Instant?
}
