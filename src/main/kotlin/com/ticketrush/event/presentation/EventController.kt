package com.ticketrush.event.presentation

import com.ticketrush.event.application.EventQueryService
import com.ticketrush.event.application.SeatMapQueryService
import com.ticketrush.shared.response.PageResponse
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.TimeUnit

@RestController
@RequestMapping("/api/events")
class EventController(
    private val eventQueryService: EventQueryService,
    private val seatMapQueryService: SeatMapQueryService,
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

    // 브라우저가 같은 지도를 1초 안에 중복 요청하지 않게 한다
    @GetMapping("/{id}/seats/status")
    fun findSeatStatus(
        @PathVariable id: Long,
    ): ResponseEntity<SeatStatusResponse> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.maxAge(1, TimeUnit.SECONDS).cachePublic())
            .body(seatMapQueryService.findStatus(id))
}
