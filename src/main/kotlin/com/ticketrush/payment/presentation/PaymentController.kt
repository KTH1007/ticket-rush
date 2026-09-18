package com.ticketrush.payment.presentation

import com.ticketrush.payment.application.PaymentCommandService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/reservations/{reservationId}/payments")
class PaymentController(
    private val paymentCommandService: PaymentCommandService,
) {
    @PostMapping
    fun confirm(
        @PathVariable reservationId: Long,
        @Valid @RequestBody request: PaymentConfirmationRequest,
    ): ResponseEntity<PaymentConfirmationResponse> {
        val result = paymentCommandService.confirmPayment(reservationId, request.holdToken)
        return ResponseEntity.status(HttpStatus.CREATED).body(PaymentConfirmationResponse.from(result))
    }
}
