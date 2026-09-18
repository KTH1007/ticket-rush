package com.ticketrush.reservation.domain

interface ReservationLookupRateLimiterPort {
    // 조회 실패 시 호출, 갱신된 실패 횟수를 반환한다
    fun recordFailure(reservationNo: String): Long

    // 현재 실패 횟수가 임계치를 넘었는지
    fun isBlocked(reservationNo: String): Boolean

    // 조회 성공 시 카운터를 지운다
    fun reset(reservationNo: String)
}
