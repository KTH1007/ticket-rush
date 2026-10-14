package com.ticketrush.queue.domain

import java.time.Instant

data class EventSchedule(
    val opensAt: Instant,
    val startsAt: Instant,
)
