package com.ticketrush.queue.domain

data class QueueStatus(
    val sequence: Long,
    val active: Boolean,
    val lastPromotedSequence: Long,
)
