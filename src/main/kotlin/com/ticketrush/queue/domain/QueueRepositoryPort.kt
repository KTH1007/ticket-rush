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

    fun findSequence(
        eventId: Long,
        token: String,
    ): Long?

    fun isActive(
        eventId: Long,
        token: String,
    ): Boolean

    fun lastPromotedSequence(eventId: Long): Long

    fun activeEventIds(): Set<Long>
}
