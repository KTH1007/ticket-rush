package com.ticketrush.payment.domain

import java.util.UUID

interface PaymentGatewayPort {
    // idempotencyKey는 PG 쪽 중복 승인 방지용
    fun charge(
        reservationId: Long,
        amount: Int,
        idempotencyKey: UUID,
    ): PaymentGatewayResult
}
