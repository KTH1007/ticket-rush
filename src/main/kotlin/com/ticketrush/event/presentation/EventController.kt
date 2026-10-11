package com.ticketrush.event.presentation

import com.ticketrush.event.application.EventQueryService
import com.ticketrush.event.application.SeatMapQueryService
import com.ticketrush.shared.response.PageResponse
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
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

    // 브라우저가 같은 지도를 1초 안에 중복 요청하지 않게 한다
    @GetMapping("/{id}/seats/status")
    fun findSeatStatus(
        @PathVariable id: Long,
    ): ResponseEntity<SeatStatusResponse> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.maxAge(1, TimeUnit.SECONDS).cachePublic())
            .body(seatMapQueryService.findStatus(id))

    // 거의 안 바뀌는 데이터라 ETag와 Cache-Control로 클라이언트 캐시를 쓰게 한다. If-None-Match가 같으면 Spring MVC가 304로 응답한다
    @GetMapping("/{id}/seats/layout")
    fun findSeatLayout(
        @PathVariable id: Long,
        @RequestHeader(HttpHeaders.ACCEPT_ENCODING, required = false) acceptEncoding: String?,
    ): ResponseEntity<ByteArray> {
        val layout = seatMapQueryService.findLayout(id)
        val builder =
            ResponseEntity
                .ok()
                .contentType(MediaType.APPLICATION_JSON)
                .eTag(layout.etag)
                .cacheControl(CacheControl.maxAge(LAYOUT_MAX_AGE_SECONDS, TimeUnit.SECONDS).cachePublic())
                .header(HttpHeaders.VARY, HttpHeaders.ACCEPT_ENCODING)
        if (!AcceptEncoding.acceptsGzip(acceptEncoding)) return builder.body(layout.json())
        return builder.header(HttpHeaders.CONTENT_ENCODING, "gzip").body(layout.gzipJson)
    }

    private companion object {
        const val LAYOUT_MAX_AGE_SECONDS = 60L
    }
}
