package com.ticketrush.payment.presentation

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import java.util.UUID

data class PaymentConfirmationRequest(
    val holdToken: UUID,
    @field:NotBlank
    val paymentKey: String,
    @field:NotBlank
    val orderId: String,
    @field:Positive
    val amount: Int,
)
