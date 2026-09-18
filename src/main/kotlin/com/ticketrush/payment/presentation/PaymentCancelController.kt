package com.ticketrush.payment.presentation

import com.ticketrush.payment.application.PaymentCancelCommandService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class PaymentCancelController(
    private val paymentCancelCommandService: PaymentCancelCommandService,
) {
    @PostMapping("/api/reservations/cancel")
    fun cancel(
        @Valid @RequestBody request: PaymentCancelRequest,
    ): PaymentCancelResponse {
        val result = paymentCancelCommandService.cancel(request.reservationNo, request.phone)
        return PaymentCancelResponse.from(result)
    }
}
