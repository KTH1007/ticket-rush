package com.ticketrush.payment.presentation

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.util.UUID

data class PaymentConfirmationRequest(
    val holdToken: UUID,
    // payment.toss_payment_key 컬럼 길이(200)
    @field:NotBlank
    @field:Size(max = 200)
    val paymentKey: String,
    // payment.toss_order_id 컬럼 길이(64)이자 Toss orderId의 최대 길이
    @field:NotBlank
    @field:Size(max = 64)
    val orderId: String,
    @field:Positive
    val amount: Int,
)
