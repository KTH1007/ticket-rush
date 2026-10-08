package com.ticketrush.payment.application

import com.ticketrush.payment.PaymentPolicyProperties
import com.ticketrush.reservation.SeatPolicyProperties
import org.springframework.stereotype.Component

// 두 모듈의 설정이 어긋나면 모든 결제나 재시도가 조용히 막히므로, 기동 때 한 번 교차 검증한다
@Component
class PaymentHoldPolicyGuard(
    policy: PaymentPolicyProperties,
    seatPolicy: SeatPolicyProperties,
) {
    init {
        require(seatPolicy.holdTtl > policy.minHoldRemaining) {
            "holdTtl은 minHoldRemaining보다 길어야 결제를 시작할 수 있습니다: holdTtl=${seatPolicy.holdTtl}, minHoldRemaining=${policy.minHoldRemaining}"
        }
        require(seatPolicy.paymentFailedHoldTtl > policy.minHoldRemaining) {
            "paymentFailedHoldTtl은 minHoldRemaining보다 길어야 거절 뒤 재시도할 수 있습니다: " +
                "paymentFailedHoldTtl=${seatPolicy.paymentFailedHoldTtl}, minHoldRemaining=${policy.minHoldRemaining}"
        }
    }
}
