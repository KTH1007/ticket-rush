package com.ticketrush.reservation

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "ticket-rush.reservation-lookup")
data class ReservationLookupPolicyProperties(
    val maxAttempts: Int,
    val blockWindow: Duration,
) {
    init {
        require(maxAttempts > 0) { "maxAttempts는 양수여야 합니다: $maxAttempts" }
        require(!blockWindow.isNegative && !blockWindow.isZero) { "blockWindow는 양수여야 합니다: $blockWindow" }
    }
}
