package com.ticketrush.queue.domain

interface QueueRepositoryPort {
    fun register(
        eventId: Long,
        token: String,
    ): Long

    fun promote(
        eventId: Long,
        count: Int,
    ): List<String>

    fun findStatus(
        eventId: Long,
        token: String,
    ): QueueStatus?

    // 등록이 통과해 대기열이 열려 있는 이벤트인지. DB를 보지 않고 Redis만으로 판단한다
    fun isActive(eventId: Long): Boolean

    fun reserveGlobalBudget(count: Int): Int

    fun activeEventIds(): Set<Long>
}
