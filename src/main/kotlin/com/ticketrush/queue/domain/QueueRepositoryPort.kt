package com.ticketrush.queue.domain

import java.time.Instant

interface QueueRepositoryPort {
    // 오픈 판정과 순번 부여를 한 번에 한다. opensAtHint는 Redis에 오픈 시각이 없을 때만 저장된다
    fun register(
        eventId: Long,
        token: String,
        opensAtHint: Instant? = null,
    ): RegisterResult

    // count는 이번 호출의 최대치다. 서비스 전체의 초당 승격 예산을 넘지 않도록 스크립트가 줄인다
    fun promote(
        eventId: Long,
        count: Int,
    ): List<String>

    fun findStatus(
        eventId: Long,
        token: String,
    ): QueueStatus?

    fun activeEventIds(): Set<Long>
}
