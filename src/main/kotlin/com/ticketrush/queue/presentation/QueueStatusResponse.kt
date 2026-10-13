package com.ticketrush.queue.presentation

data class QueueStatusResponse(
    val rank: Long,
    val nextPollIntervalMs: Long,
    val soldOut: Boolean = false,
)
