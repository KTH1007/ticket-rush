package com.ticketrush.payment

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "ticket-rush.payment")
data class PaymentPolicyProperties(
    val staleClaimTimeout: Duration,
    // 스케줄러는 yml 값을 직접 읽는다. 이 필드는 기동 때 값을 검증하고, 정책 테스트가 스케줄러 주기와 비교하는 기준이다
    val reclaimInterval: Duration,
    val refundRetryDelay: Duration = Duration.ofMinutes(5),
    val refundRetryInterval: Duration = Duration.ofSeconds(30),
    val refundMaxAttempts: Int = 5,
    val refundBacklogRefreshInterval: Duration = Duration.ofSeconds(15),
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
        require(refundRetryInterval.isPositive()) { "refundRetryInterval은 양수여야 합니다: $refundRetryInterval" }
        require(refundMaxAttempts > 0) { "refundMaxAttempts는 양수여야 합니다: $refundMaxAttempts" }
        require(refundBacklogRefreshInterval.isPositive()) {
            "refundBacklogRefreshInterval은 양수여야 합니다: $refundBacklogRefreshInterval"
        }
        require(!minHoldRemaining.isNegative && !minHoldRemaining.isZero) {
            "minHoldRemaining은 양수여야 합니다: $minHoldRemaining"
        }
    }
}
