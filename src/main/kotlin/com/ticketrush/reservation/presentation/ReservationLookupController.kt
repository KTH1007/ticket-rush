package com.ticketrush.reservation.presentation

import com.ticketrush.reservation.application.ReservationLookupQueryService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class ReservationLookupController(
    private val reservationLookupQueryService: ReservationLookupQueryService,
) {
    @PostMapping("/api/reservations/lookup")
    fun lookup(
        @Valid @RequestBody request: ReservationLookupRequest,
    ): ReservationLookupResponse {
        val result = reservationLookupQueryService.lookup(request.reservationNo, request.phone)
        return ReservationLookupResponse.from(result)
    }
}
