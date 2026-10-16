package com.ticketrush.payment.domain

import java.util.UUID

// 승인 멱등키. Toss는 에러 응답도 같은 키로 재생하므로 응답이 있었던 실패(확정 거절, 5xx)의 횟수가 오르면 키를 바꾼다. 횟수 0은 예약 키 그대로다.
// 키를 바꿔도 이미 승인된 paymentKey는 Toss가 ALREADY_PROCESSED_PAYMENT로 막고 어댑터가 조회로 판정하므로 이중 청구되지 않는다.
// paymentKey를 섞지 않는다. 이어받기 전후의 키가 같아야 이전 요청이 늦게 닿아도 Toss가 같은 승인 응답을 재생해 하나로 수렴한다
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
