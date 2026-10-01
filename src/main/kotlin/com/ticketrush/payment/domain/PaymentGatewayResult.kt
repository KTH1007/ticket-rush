package com.ticketrush.payment.domain

import java.time.LocalDateTime

// PG 승인 결과. 실제 PG 연동 없이 가짜 어댑터로 대체
sealed interface PaymentGatewayResult {
    data class Approved(
        val pgTransactionId: String,
        // PG가 승인한 시각(서울 기준). 모르면 null이고, 그땐 호출 측이 서버 시각으로 대체한다
        val approvedAt: LocalDateTime? = null,
    ) : PaymentGatewayResult

    data class Declined(
        val reason: String,
    ) : PaymentGatewayResult
}
