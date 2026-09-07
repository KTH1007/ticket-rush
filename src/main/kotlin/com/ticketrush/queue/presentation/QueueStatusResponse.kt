package com.ticketrush.queue.presentation

data class QueueStatusResponse(
    val rank: Long,
    val nextPollIntervalMs: Long,
)
