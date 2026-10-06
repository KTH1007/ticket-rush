package com.ticketrush.payment.infrastructure

import com.ticketrush.payment.domain.PaymentGatewayPort
import com.ticketrush.payment.domain.PaymentGatewayResult
import com.ticketrush.payment.domain.PaymentInquiryResult
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.UUID

// toss 프로필이 아닐 때(로컬 개발과 테스트) 쓰는 가짜 구현체. 항상 승인
@Profile("!toss")
@Component
class FakePaymentGatewayAdapter : PaymentGatewayPort {
    override fun charge(
        paymentKey: String,
        orderId: String,
        amount: Int,
        idempotencyKey: UUID,
    ): PaymentGatewayResult = PaymentGatewayResult.Approved(pgTransactionId = "FAKE-${UUID.randomUUID()}")

    // 가짜 PG엔 조회할 결제 원장이 없다
    override fun inquire(paymentKey: String): PaymentInquiryResult = PaymentInquiryResult.NotFound

    override fun refund(
        pgTransactionId: String,
        amount: Int,
        idempotencyKey: UUID,
    ): PaymentGatewayResult = PaymentGatewayResult.Approved(pgTransactionId = "FAKE-REFUND-${UUID.randomUUID()}")
}
