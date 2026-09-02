package com.ticketrush.event.presentation

import com.ticketrush.event.application.EventQueryService
import com.ticketrush.shared.response.PageResponse
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/events")
class EventController(
    private val eventQueryService: EventQueryService,
) {
    @GetMapping
    fun findEvents(
        @PageableDefault(sort = ["id"]) pageable: Pageable,
    ): PageResponse<EventSummaryResponse> = eventQueryService.findEvents(pageable)

    @GetMapping("/{id}")
    fun findEvent(
        @PathVariable id: Long,
    ): EventDetailResponse = eventQueryService.findEvent(id)

    @GetMapping("/{id}/seats")
    fun findSeats(
        @PathVariable id: Long,
    ): List<SeatResponse> = eventQueryService.findSeats(id)
}
