package com.ticketrush.payment.application

import com.ticketrush.payment.domain.Payment
import com.ticketrush.payment.domain.PaymentConflictException
import com.ticketrush.payment.domain.PaymentGatewayPort
import com.ticketrush.payment.domain.PaymentGatewayResult
import com.ticketrush.payment.domain.PaymentGatewayUnavailableException
import com.ticketrush.payment.domain.PaymentStatus
import com.ticketrush.payment.domain.RefundIdempotencyKey
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.util.UUID
import kotlin.test.Test

// 취소 요청과 재시도 스케줄러가 같은 규칙으로 시도 횟수를 세는지가 핵심이라, 결과 해석만 따로 확인한다
class PaymentRefunderTest {
    private val paymentGateway = mockk<PaymentGatewayPort>()
    private val refunder = PaymentRefunder(paymentGateway)
    private val reservationKey = UUID.randomUUID()

    @Test
    fun `승인되면 확정 결과로 돌려주고 원 거래 id와 금액과 시도 횟수에서 유도한 키로 요청한다`() {
        every { paymentGateway.refund(any(), any(), any()) } returns PaymentGatewayResult.Approved("PG-TXN")

        val outcome = refunder.refund(취소된_결제(), reservationKey)

        assertThat(outcome).isEqualTo(PaymentRefunder.Outcome.Completed(PaymentGatewayResult.Approved("PG-TXN")))
        verify(exactly = 1) { paymentGateway.refund("PG-TXN", AMOUNT, RefundIdempotencyKey.of(reservationKey, 0)) }
    }

    @Test
    fun `시도 횟수가 오르면 다른 키로 요청한다`() {
        every { paymentGateway.refund(any(), any(), any()) } returns PaymentGatewayResult.Approved("PG-TXN")

        refunder.refund(취소된_결제(refundAttemptCount = 2), reservationKey)

        verify(exactly = 1) { paymentGateway.refund("PG-TXN", AMOUNT, RefundIdempotencyKey.of(reservationKey, 2)) }
    }

    @Test
    fun `거절도 확정 결과로 돌려줘서 시도로 센다`() {
        every { paymentGateway.refund(any(), any(), any()) } returns PaymentGatewayResult.Declined("한도 초과")

        val outcome = refunder.refund(취소된_결제(), reservationKey)

        assertThat(outcome).isEqualTo(PaymentRefunder.Outcome.Completed(PaymentGatewayResult.Declined("한도 초과")))
    }

    @Test
    fun `PG가 같은 환불을 처리 중이면 시도로 세지 않는 처리 중으로 돌려준다`() {
        every { paymentGateway.refund(any(), any(), any()) } throws PaymentConflictException()

        assertThat(refunder.refund(취소된_결제(), reservationKey)).isEqualTo(PaymentRefunder.Outcome.InProgress)
    }

    @Test
    fun `PG에 닿지 못하면 시도로 세지 않는 닿지 못함으로 돌려준다`() {
        every { paymentGateway.refund(any(), any(), any()) } throws PaymentGatewayUnavailableException()

        assertThat(refunder.refund(취소된_결제(), reservationKey)).isEqualTo(PaymentRefunder.Outcome.Unreachable)
    }

    @Test
    fun `그 밖의 예외는 환불이 안 된 것으로 보고 거절로 바꿔 시도로 센다`() {
        every { paymentGateway.refund(any(), any(), any()) } throws IllegalStateException("연결이 끊겼습니다")

        val outcome = refunder.refund(취소된_결제(), reservationKey)

        assertThat(outcome).isEqualTo(PaymentRefunder.Outcome.Completed(PaymentGatewayResult.Declined("연결이 끊겼습니다")))
    }

    @Test
    fun `예외에 메시지가 없으면 예외 이름을 사유로 쓴다`() {
        every { paymentGateway.refund(any(), any(), any()) } throws IllegalStateException()

        val outcome = refunder.refund(취소된_결제(), reservationKey)

        assertThat(outcome).isEqualTo(PaymentRefunder.Outcome.Completed(PaymentGatewayResult.Declined("IllegalStateException")))
    }

    @Test
    fun `원 거래 id가 없으면 PG를 부르지 않고 실패한다`() {
        val withoutTransactionId = Payment(id = 1L, reservationId = 10L, amount = AMOUNT, status = PaymentStatus.CANCELED)

        assertThatThrownBy { refunder.refund(withoutTransactionId, reservationKey) }.isInstanceOf(IllegalArgumentException::class.java)

        verify(exactly = 0) { paymentGateway.refund(any(), any(), any()) }
    }

    private fun 취소된_결제(refundAttemptCount: Int = 0): Payment =
        Payment(
            id = 1L,
            reservationId = 10L,
            amount = AMOUNT,
            status = PaymentStatus.CANCELED,
            pgTransactionId = "PG-TXN",
            refundAttemptCount = refundAttemptCount,
        )

    companion object {
        private const val AMOUNT = 200_000
    }
}
