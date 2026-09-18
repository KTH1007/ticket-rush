package com.ticketrush.payment.domain

import java.util.UUID

interface PaymentGatewayPort {
    // idempotencyKey는 PG 쪽 중복 승인 방지용
    fun charge(
        reservationId: Long,
        amount: Int,
        idempotencyKey: UUID,
    ): PaymentGatewayResult

    // 환불. 실제 PG에선 원 승인 거래를 pgTransactionId로 특정해서 취소 요청함
    fun refund(
        pgTransactionId: String,
        amount: Int,
    ): PaymentGatewayResult
}
