package com.ticketrush.event.presentation

import com.ticketrush.event.domain.Seat
import com.ticketrush.event.domain.SeatStatus

data class SeatResponse(
    val id: Long,
    val section: String,
    val rowLabel: String,
    val seatNo: Short,
    val gradeName: String,
    val status: SeatStatus,
) {
    companion object {
        fun from(
            seat: Seat,
            gradeName: String,
        ): SeatResponse =
            SeatResponse(
                id = seat.id,
                section = seat.section,
                rowLabel = seat.rowLabel,
                seatNo = seat.seatNo,
                gradeName = gradeName,
                status = seat.status,
            )
    }
}
