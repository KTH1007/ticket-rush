package com.ticketrush.payment.application

import com.ticketrush.payment.domain.ChargeIdempotencyKey
import com.ticketrush.payment.domain.Payment
import com.ticketrush.payment.domain.PaymentGatewayPort
import com.ticketrush.payment.domain.PaymentGatewayResult
import com.ticketrush.payment.domain.PaymentGatewayUnavailableException
import com.ticketrush.reservation.domain.Reservation
import org.springframework.stereotype.Component

// 승인 호출을 한 곳으로 모아 사용자 요청과 회수 스케줄러가 같은 키 규칙을 쓰게 한다.
// 응답이 있었던 5xx만 키를 바꾸고, 응답이 없던 실패(타임아웃, 연결 실패, 서킷 OPEN)는 키를 유지해야 이미 끝난 승인은 재생되고 처리 중이면 409로 막힌다
@Component
class PaymentCharger(
    private val paymentGateway: PaymentGatewayPort,
    private val claimService: PaymentClaimService,
) {
    fun charge(
        claimed: Payment,
        reservation: Reservation,
        paymentKey: String,
        orderId: String,
        amount: Int,
    ): PaymentGatewayResult {
        val chargeKey = ChargeIdempotencyKey.of(reservation.idempotencyKey, claimed.chargeAttemptCount)
        return try {
            paymentGateway.charge(paymentKey, orderId, amount, chargeKey)
        } catch (e: PaymentGatewayUnavailableException) {
            if (e.serverResponded) claimService.recordServerError(claimed)
            throw e
        }
    }
}
