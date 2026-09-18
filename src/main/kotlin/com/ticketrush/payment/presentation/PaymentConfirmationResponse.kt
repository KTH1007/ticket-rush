package com.ticketrush.payment.presentation

import com.ticketrush.payment.domain.PaymentConfirmationResult
import java.time.LocalDateTime

data class PaymentConfirmationResponse(
    val reservationId: Long,
    val reservationNo: String,
    val amount: Int,
    val paidAt: LocalDateTime,
) {
    companion object {
        fun from(result: PaymentConfirmationResult): PaymentConfirmationResponse =
            PaymentConfirmationResponse(
                reservationId = result.payment.reservationId,
                reservationNo = result.reservationNo,
                amount = result.payment.amount,
                paidAt = requireNotNull(result.payment.paidAt) { "SUCCESS 결제인데 paidAt이 없습니다" },
            )
    }
}
