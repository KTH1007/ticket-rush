package com.ticketrush.payment.infrastructure

import com.ticketrush.payment.domain.PaymentGatewayPort
import com.ticketrush.payment.domain.PaymentGatewayResult
import org.springframework.stereotype.Component
import java.util.UUID

// 실제 PG 연동 전까지 쓰는 가짜 구현체. 항상 승인
@Component
class FakePaymentGatewayAdapter : PaymentGatewayPort {
    override fun charge(
        reservationId: Long,
        amount: Int,
        idempotencyKey: UUID,
    ): PaymentGatewayResult = PaymentGatewayResult.Approved(pgTransactionId = "FAKE-${UUID.randomUUID()}")
}
