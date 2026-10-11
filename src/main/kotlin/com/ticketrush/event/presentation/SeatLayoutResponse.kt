package com.ticketrush.event.presentation

// 등급 이름이 좌석마다 반복되지 않게 등급 표를 따로 둔다
data class SeatLayoutResponse(
    val eventId: Long,
    val seatCount: Int,
    val grades: List<GradeItem>,
    val seats: List<SeatItem>,
) {
    data class GradeItem(
        val id: Long,
        val name: String,
    )

    data class SeatItem(
        val id: Long,
        val ordinal: Int,
        val section: String,
        val rowLabel: String,
        val seatNo: Short,
        val gradeId: Long,
    )
}
