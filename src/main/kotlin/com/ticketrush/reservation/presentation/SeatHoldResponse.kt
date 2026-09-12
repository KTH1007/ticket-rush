package com.ticketrush.reservation.presentation

import com.ticketrush.reservation.domain.Reservation
import java.time.LocalDateTime

data class SeatHoldResponse(
    val reservationId: Long,
    val holdToken: String,
    val quantity: Short,
    val amount: Int,
    val holdExpiresAt: LocalDateTime,
) {
    companion object {
        fun from(reservation: Reservation): SeatHoldResponse =
            SeatHoldResponse(
                reservationId = reservation.id,
                holdToken = reservation.holdToken.toString(),
                quantity = reservation.quantity,
                amount = reservation.amount,
                holdExpiresAt = requireNotNull(reservation.holdExpiresAt) { "HOLDING 상태의 예약은 holdExpiresAt이 있어야 한다" },
            )
    }
}
