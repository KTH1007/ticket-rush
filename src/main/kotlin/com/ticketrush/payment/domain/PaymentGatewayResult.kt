package com.ticketrush.payment.domain

import java.time.LocalDateTime

// PG 승인과 환불의 확정 결과. 결과를 알 수 없는 호출 실패(5xx, 타임아웃)는 이 타입이 아니라 예외로 전파된다
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
