package com.ticketrush.reservation.domain

interface ReservationLookupRateLimiterPort {
    // 확인과 예약(카운터 증가)을 한 번에 원자적으로 처리한다. 이미 임계치를 넘었으면
    // false(차단, 카운터 안 건드림), 아니면 카운터를 먼저 올리고 true(허용)
    fun tryReserveAttempt(reservationNo: String): Boolean

    // 조회 성공 시 내가 예약해둔 슬롯 하나만 돌려준다(카운터 1 감소). 전체를 지우면
    // 동시에 진행 중인 다른 요청의 실패 기록까지 같이 사라질 수 있어서 감소만 한다
    fun releaseAttempt(reservationNo: String)
}
