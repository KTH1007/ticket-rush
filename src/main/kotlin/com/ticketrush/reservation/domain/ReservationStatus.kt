package com.ticketrush.reservation.domain

enum class ReservationStatus(
    val description: String,
) {
    HOLDING("좌석 선점, 결제 대기"),
    PAID("결제 확정"),
    CANCELED("사용자 취소"),
    EXPIRED("홀드 시간 초과로 자동 해제"),
}
