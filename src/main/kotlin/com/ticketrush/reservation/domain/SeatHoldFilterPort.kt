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

    // 결제 실패로 DB 홀드가 짧아졌을 때. 내 마크의 남은 시간이 더 길면 줄이고, 늘리지는 않는다
    fun shortenAfterPaymentFailure(
        eventId: Long,
        seatIds: List<Long>,
        holdToken: String,
    )

    // 판매된 좌석의 마크를 오래 둬서 이후 시도를 DB 전에 거른다
    fun extendForSold(
        eventId: Long,
        seatIds: List<Long>,
        holdToken: String,
    )
}
