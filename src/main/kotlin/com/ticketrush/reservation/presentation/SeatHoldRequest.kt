package com.ticketrush.reservation.presentation

import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

data class SeatHoldRequest(
    @field:NotEmpty
    @field:Size(min = 1, max = 2)
    val seatIds: List<Long>,
    @field:Pattern(regexp = "^01[0-9]{8,9}$", message = "올바른 휴대폰 번호 형식이 아닙니다")
    val phoneNumber: String,
)
