package com.ticketrush.payment.infrastructure

import com.ticketrush.payment.domain.PaymentGatewayPort
import com.ticketrush.payment.domain.PaymentGatewayResult
import com.ticketrush.payment.domain.PaymentInquiryResult
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

// 로컬 개발과 테스트에서 쓰는 가짜 구현체. 항상 승인
@ConditionalOnPaymentGateway(PaymentGatewayType.FAKE)
@Component
class FakePaymentGatewayAdapter(
    // 부하 측정에서 실제 PG의 응답 지연을 흉내 낸다. 기본은 지연 없음
    @Value("\${ticket-rush.payment.fake-delay:0ms}") private val delay: Duration,
) : PaymentGatewayPort {
    override fun charge(
        paymentKey: String,
        orderId: String,
        amount: Int,
        idempotencyKey: UUID,
    ): PaymentGatewayResult {
        Thread.sleep(delay)
        return PaymentGatewayResult.Approved(pgTransactionId = "FAKE-${UUID.randomUUID()}")
    }

    // 가짜 PG엔 조회할 결제 원장이 없다
    override fun inquire(paymentKey: String): PaymentInquiryResult = PaymentInquiryResult.NotFound

    override fun refund(
        pgTransactionId: String,
        amount: Int,
        idempotencyKey: UUID,
    ): PaymentGatewayResult = PaymentGatewayResult.Approved(pgTransactionId = "FAKE-REFUND-${UUID.randomUUID()}")
}
