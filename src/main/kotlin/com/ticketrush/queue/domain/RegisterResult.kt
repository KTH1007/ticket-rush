package com.ticketrush.queue.domain

import java.time.Instant

sealed interface RegisterResult {
    data class Registered(
        val sequence: Long,
    ) : RegisterResult

    data class NotOpen(
        val opensAt: Instant,
    ) : RegisterResult

    // 공연이 이미 시작해 새로 줄을 세우지 않는다
    data class Closed(
        val startsAt: Instant,
    ) : RegisterResult

    // Redis에 공연 일정이 없다. 호출 측이 DB에서 읽어 힌트로 다시 등록한다
    data object ScheduleUnknown : RegisterResult
}
