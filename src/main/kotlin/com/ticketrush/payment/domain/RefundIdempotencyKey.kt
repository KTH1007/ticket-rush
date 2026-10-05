package com.ticketrush.payment.domain

import java.util.UUID

// 환불 멱등키. 새 컬럼 없이 예약 키에서 유도해 같은 예약의 환불은 재시도해도 항상 같은 키를 쓴다
object RefundIdempotencyKey {
    fun of(reservationIdempotencyKey: UUID): UUID = UUID.nameUUIDFromBytes("refund:$reservationIdempotencyKey".toByteArray())
}
