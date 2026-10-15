package com.ticketrush.reservation

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "ticket-rush.reservation-lookup")
data class ReservationLookupPolicyProperties(
    val maxAttempts: Int,
    val blockWindow: Duration,
    // 예약번호를 바꿔 가며 찍는 시도를 막는 IP 기준 한도
    val ipMaxAttempts: Int = DEFAULT_IP_MAX_ATTEMPTS,
    val ipBlockWindow: Duration = Duration.ofMinutes(DEFAULT_IP_BLOCK_MINUTES),
) {
    init {
        require(maxAttempts > 0) { "maxAttempts는 양수여야 합니다: $maxAttempts" }
        require(!blockWindow.isNegative && !blockWindow.isZero) { "blockWindow는 양수여야 합니다: $blockWindow" }
        require(ipMaxAttempts > 0) { "ipMaxAttempts는 양수여야 합니다: $ipMaxAttempts" }
        // Redis EX는 초 단위라 1초 미만은 0으로 잘려 키가 바로 사라진다
        require(ipBlockWindow >= Duration.ofSeconds(1)) { "ipBlockWindow는 1초 이상이어야 합니다: $ipBlockWindow" }
    }

    private companion object {
        const val DEFAULT_IP_MAX_ATTEMPTS = 50
        const val DEFAULT_IP_BLOCK_MINUTES = 10L
    }
}
