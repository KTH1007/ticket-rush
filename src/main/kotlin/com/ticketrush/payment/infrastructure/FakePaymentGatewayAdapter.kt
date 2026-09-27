package com.ticketrush.payment.infrastructure

import com.ticketrush.payment.domain.PaymentGatewayPort
import com.ticketrush.payment.domain.PaymentGatewayResult
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.UUID

// 실제 PG 연동 전까지 쓰는 가짜 구현체. 항상 승인
@Profile("!toss")
@Component
class FakePaymentGatewayAdapter : PaymentGatewayPort {
    override fun charge(
        paymentKey: String,
        orderId: String,
        amount: Int,
        idempotencyKey: UUID,
    ): PaymentGatewayResult = PaymentGatewayResult.Approved(pgTransactionId = "FAKE-${UUID.randomUUID()}")

    override fun refund(
        pgTransactionId: String,
        amount: Int,
    ): PaymentGatewayResult = PaymentGatewayResult.Approved(pgTransactionId = "FAKE-REFUND-${UUID.randomUUID()}")
}
