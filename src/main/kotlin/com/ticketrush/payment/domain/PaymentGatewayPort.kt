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
    // idempotencyKey는 처리 중인 같은 요청을 PG가 중복 취소하지 않게 하는 키다. PG가 실패 응답도 키에 묶어 재생하므로 실패하면 바꾼다
    fun refund(
        pgTransactionId: String,
        amount: Int,
        idempotencyKey: UUID,
    ): PaymentGatewayResult
}
