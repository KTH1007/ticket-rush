package com.ticketrush.queue.domain

import java.time.Instant

interface QueueRepositoryPort {
    // 오픈 판정과 순번 부여를 한 번에 한다. opensAtHint는 Redis에 오픈 시각이 없을 때만 저장된다
    fun register(
        eventId: Long,
        token: String,
        opensAtHint: Instant? = null,
    ): RegisterResult

    fun promote(
        eventId: Long,
        count: Int,
    ): List<String>

    fun findStatus(
        eventId: Long,
        token: String,
    ): QueueStatus?

    fun reserveGlobalBudget(count: Int): Int

    fun activeEventIds(): Set<Long>
}
