package com.ticketrush.payment

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "ticket-rush.payment")
data class PaymentPolicyProperties(
    val staleClaimTimeout: Duration,
    val reclaimInterval: Duration,
    val refundRetryDelay: Duration = Duration.ofMinutes(5),
    val refundMaxAttempts: Int = 5,
    val minHoldRemaining: Duration = Duration.ofSeconds(15),
) {
    init {
        require(!staleClaimTimeout.isNegative && !staleClaimTimeout.isZero) {
            "staleClaimTimeout은 양수여야 합니다: $staleClaimTimeout"
        }
        require(!reclaimInterval.isNegative && !reclaimInterval.isZero) {
            "reclaimInterval은 양수여야 합니다: $reclaimInterval"
        }
        require(!refundRetryDelay.isNegative && !refundRetryDelay.isZero) {
            "refundRetryDelay는 양수여야 합니다: $refundRetryDelay"
        }
        require(refundMaxAttempts > 0) { "refundMaxAttempts는 양수여야 합니다: $refundMaxAttempts" }
        require(!minHoldRemaining.isNegative && !minHoldRemaining.isZero) {
            "minHoldRemaining은 양수여야 합니다: $minHoldRemaining"
        }
    }
}
