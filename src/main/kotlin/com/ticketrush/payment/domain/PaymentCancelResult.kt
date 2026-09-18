package com.ticketrush.payment.domain

data class PaymentCancelResult(
    val payment: Payment,
    val reservationNo: String,
)
