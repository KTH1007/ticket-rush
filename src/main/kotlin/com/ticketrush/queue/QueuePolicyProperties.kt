package com.ticketrush.queue

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

// 대기열 설정을 한곳에 모아 기동 때 검증한다. @Value로 여러 클래스가 따로 읽으면 0이나 음수도 그냥 통과했다
@ConfigurationProperties(prefix = "ticket-rush.queue")
data class QueuePolicyProperties(
    val tokenTtl: Duration,
    val activeTtl: Duration,
    val promotionBudgetPerSecond: Int,
    val pollInterval: PollInterval,
    // 이 순위 이내면 가까운 간격(near)으로 폴링한다
    val nearRankThreshold: Long = 100,
    // 오픈 시각 변경이 반영되는 데 걸리는 최대 시간이자 DB 재조회 주기
    val scheduleCacheTtl: Duration = Duration.ofMinutes(10),
) {
    data class PollInterval(
        val far: Duration,
        val near: Duration,
    )

    init {
        require(tokenTtl.isPositive()) { "tokenTtl은 양수여야 합니다: $tokenTtl" }
        require(activeTtl.isPositive()) { "activeTtl은 양수여야 합니다: $activeTtl" }
        require(promotionBudgetPerSecond > 0) { "promotionBudgetPerSecond는 양수여야 합니다: $promotionBudgetPerSecond" }
        require(pollInterval.far.isPositive()) { "pollInterval.far는 양수여야 합니다: ${pollInterval.far}" }
        require(pollInterval.near.isPositive()) { "pollInterval.near는 양수여야 합니다: ${pollInterval.near}" }
        require(pollInterval.near <= pollInterval.far) {
            "pollInterval.near는 far보다 길 수 없습니다: near=${pollInterval.near}, far=${pollInterval.far}"
        }
        require(nearRankThreshold > 0) { "nearRankThreshold는 양수여야 합니다: $nearRankThreshold" }
        require(scheduleCacheTtl.isPositive()) { "scheduleCacheTtl은 양수여야 합니다: $scheduleCacheTtl" }
    }
}

private fun Duration.isPositive(): Boolean = !isNegative && !isZero
