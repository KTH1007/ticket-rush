package com.ticketrush.reservation.presentation

import com.ticketrush.reservation.domain.ReservationLookupResult
import com.ticketrush.reservation.domain.ReservationLookupSeat
import com.ticketrush.reservation.domain.ReservationStatus

data class ReservationLookupResponse(
    val reservationId: Long,
    val eventId: Long,
    val reservationNo: String,
    val status: ReservationStatus,
    val statusDescription: String,
    val amount: Int,
    val seats: List<ReservationLookupSeatResponse>,
) {
    companion object {
        fun from(result: ReservationLookupResult): ReservationLookupResponse {
            val reservation = result.reservation
            return ReservationLookupResponse(
                reservationId = reservation.id,
                eventId = reservation.eventId,
                reservationNo = requireNotNull(reservation.reservationNo) { "조회 결과인데 예매번호가 없습니다: ${reservation.id}" },
                status = reservation.status,
                statusDescription = reservation.status.description,
                amount = reservation.amount,
                seats = result.seats.map { ReservationLookupSeatResponse.from(it) },
            )
        }
    }
}

data class ReservationLookupSeatResponse(
    val section: String,
    val rowLabel: String,
    val seatNo: Short,
    val gradeName: String,
) {
    companion object {
        fun from(seat: ReservationLookupSeat): ReservationLookupSeatResponse =
            ReservationLookupSeatResponse(
                section = seat.section,
                rowLabel = seat.rowLabel,
                seatNo = seat.seatNo,
                gradeName = seat.gradeName,
            )
    }
}
