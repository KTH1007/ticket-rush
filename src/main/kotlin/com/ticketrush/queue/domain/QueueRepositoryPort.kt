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

    fun reserveGlobalBudget(count: Int): Int

    fun activeEventIds(): Set<Long>
}
