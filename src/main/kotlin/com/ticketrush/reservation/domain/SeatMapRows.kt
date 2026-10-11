package com.ticketrush.reservation.domain

// 좌석 지도용 조회 전용 행. 좌석 수만 개를 엔티티로 만들면 요청당 할당이 커서 필요한 컬럼만 담는다
data class SeatLayoutRow(
    val id: Long,
    val ordinal: Int,
    val section: String,
    val rowLabel: String,
    val seatNo: Short,
    val gradeId: Long,
)

data class SeatStatusRow(
    val ordinal: Int,
    val status: SeatStatus,
)
