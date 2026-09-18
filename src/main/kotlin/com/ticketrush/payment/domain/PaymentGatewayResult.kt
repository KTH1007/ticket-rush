package com.ticketrush.payment.domain

// PG 승인 결과. 실제 PG 연동 없이 가짜 어댑터로 대체
sealed interface PaymentGatewayResult {
    data class Approved(
        val pgTransactionId: String,
    ) : PaymentGatewayResult

    data class Declined(
        val reason: String,
    ) : PaymentGatewayResult
}
