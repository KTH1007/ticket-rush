package com.ticketrush.payment.domain

import java.util.UUID

// 환불 멱등키. Toss는 에러 응답도 같은 키로 재생하므로 실패해서 시도 횟수가 오르면 키를 바꾼다. 횟수 0은 첫 시도의 키와 같다.
// 키를 바꿔도 이미 취소된 결제는 Toss가 ALREADY_CANCELED_PAYMENT로 막고 어댑터가 조회로 판정하므로 이중 환불되지 않는다
object RefundIdempotencyKey {
    fun of(
        reservationIdempotencyKey: UUID,
        attemptCount: Int,
    ): UUID {
        val seed = if (attemptCount == 0) "refund:$reservationIdempotencyKey" else "refund:$reservationIdempotencyKey:$attemptCount"
        return UUID.nameUUIDFromBytes(seed.toByteArray())
    }
}
