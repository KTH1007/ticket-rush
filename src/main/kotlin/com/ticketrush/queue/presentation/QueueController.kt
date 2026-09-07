package com.ticketrush.queue.presentation

import com.ticketrush.queue.application.QueueCommandService
import com.ticketrush.queue.application.QueueQueryService
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/events/{eventId}/queues")
class QueueController(
    private val queueCommandService: QueueCommandService,
    private val queueQueryService: QueueQueryService,
) {
    @PostMapping
    fun register(
        @PathVariable eventId: Long,
    ): ResponseEntity<Unit> {
        val token = queueCommandService.register(eventId)
        val headers = HttpHeaders()
        headers.add("X-Queue-Token", token)
        return ResponseEntity(headers, HttpStatus.CREATED)
    }

    @GetMapping("/me")
    fun checkStatus(
        @PathVariable eventId: Long,
        @RequestHeader("X-Queue-Token") token: String,
    ): QueueStatusResponse = queueQueryService.checkStatus(eventId, token)
}
