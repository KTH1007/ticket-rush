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

    // paymentKey로 PG의 결제 상태를 조회한다. 승인 여부가 불확실할 때 PG에 직접 확인하는 용도
    fun inquire(paymentKey: String): PaymentInquiryResult

    // 환불. 실제 PG에선 원 승인 거래를 pgTransactionId로 특정해서 취소 요청함.
    // idempotencyKey는 재시도해도 PG가 중복 취소하지 않게 하는 키라 같은 환불엔 항상 같은 값을 쓴다
    fun refund(
        pgTransactionId: String,
        amount: Int,
        idempotencyKey: UUID,
    ): PaymentGatewayResult
}
