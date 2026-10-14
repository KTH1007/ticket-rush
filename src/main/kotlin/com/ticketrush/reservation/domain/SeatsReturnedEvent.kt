package com.ticketrush.reservation.domain

import java.util.UUID

data class SeatsReturnedEvent(
    val eventId: Long,
    val seatIds: List<Long>,
    val holdToken: UUID,
)
