package com.ticketrush.reservation.presentation

import com.ticketrush.reservation.application.ReservationLookupQueryService
import com.ticketrush.shared.ClientIp
import jakarta.servlet.http.HttpServletRequest
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
        servletRequest: HttpServletRequest,
    ): ReservationLookupResponse {
        val result = reservationLookupQueryService.lookup(request.reservationNo, request.phone, ClientIp.from(servletRequest))
        return ReservationLookupResponse.from(result)
    }
}
