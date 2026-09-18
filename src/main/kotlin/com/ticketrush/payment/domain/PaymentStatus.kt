package com.ticketrush.payment.domain

enum class PaymentStatus(
    val description: String,
) {
    PENDING("PG 승인 대기"),
    SUCCESS("승인 완료"),
    FAILED("PG 거절 또는 타임아웃"),
    CANCELED("취소 접수"),
    REFUNDED("환불 완료"),
}
