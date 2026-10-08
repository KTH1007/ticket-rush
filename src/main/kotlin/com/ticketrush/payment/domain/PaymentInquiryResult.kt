package com.ticketrush.payment.domain

import java.time.LocalDateTime

// PG에 승인 여부를 직접 물어본 결과. 승인 요청(PaymentGatewayResult)과 달리 상태를 바꾸지 않는다
sealed interface PaymentInquiryResult {
    data class Done(
        val pgTransactionId: String,
        // PG가 승인한 시각(서울 기준). 모르면 null
        val approvedAt: LocalDateTime? = null,
        // 호출 측이 다른 주문의 결제를 우리 결제로 착각하지 않도록 대조하는 값. 응답에 없으면 null이고 불일치로 본다
        val orderId: String? = null,
        val totalAmount: Int? = null,
    ) : PaymentInquiryResult

    // DONE이 아닌 모든 status(IN_PROGRESS, ABORTED, EXPIRED, CANCELED 등). 승인된 결제가 아니다
    data class NotApproved(
        val status: String,
    ) : PaymentInquiryResult

    // PG에 그 paymentKey의 결제가 없다
    data object NotFound : PaymentInquiryResult
}
