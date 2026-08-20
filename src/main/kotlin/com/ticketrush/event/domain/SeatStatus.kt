package com.ticketrush.event.domain

enum class SeatStatus(
    val description: String,
) {
    AVAILABLE("예약 가능"),
    HELD("선점 중"),
    SOLD("판매 완료"),
}
