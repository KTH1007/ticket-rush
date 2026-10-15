package com.ticketrush.reservation.domain

import com.ticketrush.shared.ClientIp
import java.time.Duration

interface ReservationLookupRateLimiterPort {
    // 확인과 예약(카운터 증가)을 한 번에 원자적으로 처리한다. 이미 임계치를 넘었으면
    // false(차단, 카운터 안 건드림), 아니면 카운터를 먼저 올리고 true(허용)
    fun tryReserveAttempt(reservationNo: String): Boolean

    // 조회 성공 시 내가 예약해둔 슬롯 하나만 돌려준다(카운터 1 감소). 전체를 지우면
    // 동시에 진행 중인 다른 요청의 실패 기록까지 같이 사라질 수 있어서 감소만 한다
    fun releaseAttempt(reservationNo: String)

    // 예약번호를 바꿔 가며 찍는 시도를 막는 IP 기준 한도. 규칙은 예약번호 기준과 같다
    fun tryReserveIpAttempt(clientIp: ClientIp): Boolean

    fun releaseIpAttempt(clientIp: ClientIp)

    // 차단된 뒤 다시 시도해도 되는 때까지 남은 시간. 차단 경로에서만 부른다
    fun retryAfter(reservationNo: String): Duration

    fun retryAfterForIp(clientIp: ClientIp): Duration

    // IP를 먼저 본다. 번호를 바꿔 가며 찍는 시도를 번호별 한도보다 먼저 거른다.
    // 번호 한도에 막힌 시도는 IP 슬롯을 돌려주지 않는다(막힌 번호를 두드리는 것도 그 IP의 시도다)
    fun reserveOrThrow(
        reservationNo: String,
        clientIp: ClientIp,
    ) {
        if (!tryReserveIpAttempt(clientIp)) throw ReservationLookupRateLimitedException(retryAfterForIp(clientIp))
        if (!tryReserveAttempt(reservationNo)) throw ReservationLookupRateLimitedException(retryAfter(reservationNo))
    }

    fun releaseAttempt(
        reservationNo: String,
        clientIp: ClientIp,
    ) {
        releaseAttempt(reservationNo)
        releaseIpAttempt(clientIp)
    }
}
