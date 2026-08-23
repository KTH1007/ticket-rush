package com.ticketrush.event.presentation

import com.ticketrush.event.domain.Event
import com.ticketrush.event.domain.Grade
import java.time.LocalDateTime

data class EventDetailResponse(
    val id: Long,
    val title: String,
    val venue: String,
    val description: String?,
    val opensAt: LocalDateTime,
    val startsAt: LocalDateTime,
    val grades: List<GradeResponse>,
) {
    companion object {
        fun from(
            event: Event,
            grades: List<Grade>,
        ): EventDetailResponse =
            EventDetailResponse(
                id = event.id,
                title = event.title,
                venue = event.venue,
                description = event.description,
                opensAt = event.opensAt,
                startsAt = event.startsAt,
                grades = grades.map { GradeResponse.from(it) },
            )
    }
}
