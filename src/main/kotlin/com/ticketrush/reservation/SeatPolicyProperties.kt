package com.ticketrush.reservation

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "ticket-rush.seat")
data class SeatPolicyProperties(
    val holdTtl: Duration,
    val maxPerPhone: Int,
) {
    init {
        require(!holdTtl.isNegative && !holdTtl.isZero) { "holdTtl은 양수여야 합니다: $holdTtl" }
        require(maxPerPhone >= 1) { "maxPerPhone은 1 이상이어야 합니다: $maxPerPhone" }
    }
}
