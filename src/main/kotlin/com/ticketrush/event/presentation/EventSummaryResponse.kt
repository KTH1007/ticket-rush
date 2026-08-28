package com.ticketrush.event.presentation

import com.ticketrush.event.domain.Event
import java.time.LocalDateTime

data class EventSummaryResponse(
    val id: Long,
    val title: String,
    val venue: String,
    val opensAt: LocalDateTime,
    val startsAt: LocalDateTime,
) {
    companion object {
        fun from(event: Event): EventSummaryResponse =
            EventSummaryResponse(
                id = event.id,
                title = event.title,
                venue = event.venue,
                opensAt = event.opensAt,
                startsAt = event.startsAt,
            )
    }
}
