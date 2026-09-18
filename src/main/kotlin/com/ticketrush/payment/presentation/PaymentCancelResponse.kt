package com.ticketrush.payment.presentation

import com.ticketrush.payment.domain.PaymentCancelResult
import com.ticketrush.payment.domain.PaymentStatus

data class PaymentCancelResponse(
    val reservationNo: String,
    val paymentStatus: PaymentStatus,
    val paymentStatusDescription: String,
    val amount: Int,
) {
    companion object {
        fun from(result: PaymentCancelResult): PaymentCancelResponse =
            PaymentCancelResponse(
                reservationNo = result.reservationNo,
                paymentStatus = result.payment.status,
                paymentStatusDescription = result.payment.status.description,
                amount = result.payment.amount,
            )
    }
}
