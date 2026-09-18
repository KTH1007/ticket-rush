package com.ticketrush.payment.presentation

import java.util.UUID

data class PaymentConfirmationRequest(
    val holdToken: UUID,
)
