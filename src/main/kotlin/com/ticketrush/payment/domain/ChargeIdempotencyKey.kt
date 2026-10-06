package com.ticketrush.payment.domain

import java.util.UUID

// 승인 멱등키. Toss는 에러 응답도 같은 키로 재생하므로 확정 거절로 끝난 횟수가 오르면 키를 바꾼다. 횟수 0은 예약 키 그대로다.
// 키를 바꿔도 이미 승인된 paymentKey는 Toss가 ALREADY_PROCESSED_PAYMENT로 막고 어댑터가 조회로 판정하므로 이중 청구되지 않는다
object ChargeIdempotencyKey {
    fun of(
        reservationIdempotencyKey: UUID,
        failedAttempts: Int,
    ): UUID =
        if (failedAttempts == 0) {
            reservationIdempotencyKey
        } else {
            UUID.nameUUIDFromBytes("charge:$reservationIdempotencyKey:$failedAttempts".toByteArray())
        }
}
