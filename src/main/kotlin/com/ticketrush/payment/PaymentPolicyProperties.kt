package com.ticketrush.payment

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "ticket-rush.payment")
data class PaymentPolicyProperties(
    val staleClaimTimeout: Duration,
    val reclaimInterval: Duration,
) {
    init {
        require(!staleClaimTimeout.isNegative && !staleClaimTimeout.isZero) {
            "staleClaimTimeout은 양수여야 합니다: $staleClaimTimeout"
        }
        require(!reclaimInterval.isNegative && !reclaimInterval.isZero) {
            "reclaimInterval은 양수여야 합니다: $reclaimInterval"
        }
    }
}
