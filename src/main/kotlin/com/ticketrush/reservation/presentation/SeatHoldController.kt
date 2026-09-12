package com.ticketrush.reservation.presentation

import com.ticketrush.reservation.application.ReservationCommandService
import com.ticketrush.reservation.domain.SeatSelection
import com.ticketrush.shared.PhoneHasher
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/events/{eventId}/seats")
class SeatHoldController(
    private val reservationCommandService: ReservationCommandService,
    private val phoneHasher: PhoneHasher,
) {
    @PostMapping("/hold")
    fun hold(
        @PathVariable eventId: Long,
        @Valid @RequestBody request: SeatHoldRequest,
    ): ResponseEntity<SeatHoldResponse> {
        val phoneHash = phoneHasher.hash(request.phoneNumber)
        val reservation =
            reservationCommandService.holdSeats(
                eventId = eventId,
                seatSelection = SeatSelection(request.seatIds),
                phoneHash = phoneHash,
            )
        return ResponseEntity.status(HttpStatus.CREATED).body(SeatHoldResponse.from(reservation))
    }
}
