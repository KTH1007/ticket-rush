package com.ticketrush.payment.domain

import java.util.UUID

interface PaymentGatewayPort {
    // paymentKey/orderId는 클라이언트가 결제창(위젯) 인증 후 받아온 값. idempotencyKey는 HTTP
    // 헤더로 보내 재시도해도 PG가 중복 승인하지 않게 한다
    fun charge(
        paymentKey: String,
        orderId: String,
        amount: Int,
        idempotencyKey: UUID,
    ): PaymentGatewayResult

    // 환불. 실제 PG에선 원 승인 거래를 pgTransactionId로 특정해서 취소 요청함
    fun refund(
        pgTransactionId: String,
        amount: Int,
    ): PaymentGatewayResult
}
