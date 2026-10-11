package com.ticketrush.queue.domain

import java.time.Instant

sealed interface RegisterResult {
    data class Registered(
        val sequence: Long,
    ) : RegisterResult

    data class NotOpen(
        val opensAt: Instant,
    ) : RegisterResult

    // Redis에 오픈 시각이 없다. 호출 측이 DB에서 읽어 힌트로 다시 등록한다
    data object ScheduleUnknown : RegisterResult
}
