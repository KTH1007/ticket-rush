package com.ticketrush.reservation.domain

interface SeatHoldFilterPort {
    // 1차 필터. 요청한 좌석을 전부 잡으면 true, 하나라도 이미 잡혀있으면 false 후 잡은 거 풀기
    fun tryClaim(
        eventId: Long,
        seatIds: List<Long>,
        holdToken: String,
    ): Boolean

    // DB 최종 실패 시 이미 잡아둔 키 되돌림
    fun release(
        eventId: Long,
        seatIds: List<Long>,
        holdToken: String,
    )
}
