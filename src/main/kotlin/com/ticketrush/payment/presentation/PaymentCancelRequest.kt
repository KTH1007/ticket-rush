package com.ticketrush.payment.presentation

import com.ticketrush.shared.ReservationNoFormat
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern

data class PaymentCancelRequest(
    @field:NotBlank
    @field:Pattern(regexp = ReservationNoFormat.PATTERN, message = ReservationNoFormat.MESSAGE)
    val reservationNo: String,
    @field:Pattern(regexp = "^01[0-9]{8,9}$", message = "올바른 휴대폰 번호 형식이 아닙니다")
    val phone: String,
)
