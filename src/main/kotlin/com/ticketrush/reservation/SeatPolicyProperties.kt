package com.ticketrush.reservation

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "ticket-rush.seat")
data class SeatPolicyProperties(
    val holdTtl: Duration,
    val maxPerPhone: Int,
    val paymentFailedHoldTtl: Duration,
) {
    init {
        require(!holdTtl.isNegative && !holdTtl.isZero) { "holdTtl은 양수여야 합니다: $holdTtl" }
        // 상한 2는 매직 넘버가 아니라 DB 제약(ck_seat_slot_no CHECK (slot_no IN (1, 2)))과
        // SeatSelection.MAX_SIZE에 맞춘 값이다. 이보다 큰 값은 부팅은 통과하지만
        // availableSlots()가 배정한 slot이 그 CHECK에 걸려 런타임에만 터진다.
        require(maxPerPhone in MIN_PER_PHONE..MAX_PER_PHONE) {
            "maxPerPhone은 ${MIN_PER_PHONE}..${MAX_PER_PHONE} 범위여야 합니다: $maxPerPhone"
        }
        require(!paymentFailedHoldTtl.isNegative && !paymentFailedHoldTtl.isZero) {
            "paymentFailedHoldTtl은 양수여야 합니다: $paymentFailedHoldTtl"
        }
        // 결제 실패 시 "줄어드는" 시간이라는 의미 자체가 원래 홀드 시간보다 짧아야 성립한다.
        require(paymentFailedHoldTtl < holdTtl) {
            "paymentFailedHoldTtl은 holdTtl보다 짧아야 합니다: paymentFailedHoldTtl=$paymentFailedHoldTtl, holdTtl=$holdTtl"
        }
    }

    companion object {
        private const val MIN_PER_PHONE = 1
        private const val MAX_PER_PHONE = 2
    }
}
