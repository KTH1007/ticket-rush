package com.ticketrush.event.application

import com.ticketrush.event.domain.EventNotFoundException
import com.ticketrush.event.domain.EventQueryPort
import com.ticketrush.event.presentation.EventDetailResponse
import com.ticketrush.event.presentation.EventSummaryResponse
import com.ticketrush.reservation.domain.GradeQueryPort
import com.ticketrush.shared.response.PageResponse
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class EventQueryService(
    private val eventRepository: EventQueryPort,
    private val gradeRepository: GradeQueryPort,
) {
    fun findEvents(pageable: Pageable): PageResponse<EventSummaryResponse> =
        PageResponse.from(eventRepository.findAll(pageable).map { EventSummaryResponse.from(it) })

    fun findEvent(id: Long): EventDetailResponse {
        val event = eventRepository.findById(id) ?: throw EventNotFoundException(id)
        val grades = gradeRepository.findAllByEventId(id)
        return EventDetailResponse.from(event, grades)
    }
}
