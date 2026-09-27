package com.ticketrush.outbox

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "ticket-rush.outbox")
data class OutboxPolicyProperties(
    val pollInterval: Duration,
    val chunkSize: Int,
    val maxAttempts: Int,
    val retryDelay: Duration,
    val staleClaimTimeout: Duration,
    val reclaimInterval: Duration,
) {
    init {
        require(!pollInterval.isNegative && !pollInterval.isZero) { "pollInterval은 양수여야 합니다: $pollInterval" }
        require(chunkSize > 0) { "chunkSize는 양수여야 합니다: $chunkSize" }
        require(maxAttempts > 0) { "maxAttempts는 양수여야 합니다: $maxAttempts" }
        require(!retryDelay.isNegative && !retryDelay.isZero) { "retryDelay는 양수여야 합니다: $retryDelay" }
        require(!staleClaimTimeout.isNegative && !staleClaimTimeout.isZero) {
            "staleClaimTimeout은 양수여야 합니다: $staleClaimTimeout"
        }
        require(!reclaimInterval.isNegative && !reclaimInterval.isZero) {
            "reclaimInterval은 양수여야 합니다: $reclaimInterval"
        }
    }
}
