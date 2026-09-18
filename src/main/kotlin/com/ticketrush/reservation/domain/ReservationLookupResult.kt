package com.ticketrush.reservation.domain

data class ReservationLookupResult(
    val reservation: Reservation,
    val seats: List<ReservationLookupSeat>,
)

data class ReservationLookupSeat(
    val section: String,
    val rowLabel: String,
    val seatNo: Short,
    val gradeName: String,
)
