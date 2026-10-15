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
    // 매진 여부를 캐시하는 시간. 취소로 좌석이 풀리면 이 시간 안에 승격이 다시 시작된다
    val broadcastCacheTtl: Duration = Duration.ofSeconds(1),
    // 한 IP가 한 공연의 대기열에 받을 수 있는 토큰 수
    val registerLimit: RegisterLimit = RegisterLimit(),
) {
    data class PollInterval(
        val far: Duration,
        val near: Duration,
        // 순번이 아주 먼 사람의 간격 상한. far는 하한이다
        val farMax: Duration = Duration.ofMinutes(5),
        // 간격에 더하는 무작위 비율(0.2면 ±20%). 같은 순간에 줄 선 사람들이 같은 순간에 다시 조회하는 것을 흩뜨린다
        val jitterRatio: Double = 0.2,
    )

    data class RegisterLimit(
        val maxPerIp: Int = DEFAULT_MAX_PER_IP,
        val window: Duration = Duration.ofMinutes(1),
    )

    init {
        require(tokenTtl.isAtLeastOneSecond()) { "tokenTtl은 1초 이상이어야 합니다: $tokenTtl" }
        require(activeTtl.isAtLeastOneSecond()) { "activeTtl은 1초 이상이어야 합니다: $activeTtl" }
        require(promotionBudgetPerSecond > 0) { "promotionBudgetPerSecond는 양수여야 합니다: $promotionBudgetPerSecond" }
        require(pollInterval.far.isPositive()) { "pollInterval.far는 양수여야 합니다: ${pollInterval.far}" }
        require(pollInterval.near.isPositive()) { "pollInterval.near는 양수여야 합니다: ${pollInterval.near}" }
        require(pollInterval.near <= pollInterval.far) {
            "pollInterval.near는 far보다 길 수 없습니다: near=${pollInterval.near}, far=${pollInterval.far}"
        }
        require(pollInterval.far <= pollInterval.farMax) {
            "pollInterval.farMax는 far보다 짧을 수 없습니다: farMax=${pollInterval.farMax}, far=${pollInterval.far}"
        }
        require(pollInterval.jitterRatio in 0.0..1.0) { "pollInterval.jitterRatio는 0 이상 1 이하여야 합니다: ${pollInterval.jitterRatio}" }
        // 조회 간격이 토큰 수명을 넘으면 다음 조회 전에 토큰이 만료된다. Redis에는 초 단위로 잘려 전달되므로 그 값과 비교한다
        require(pollInterval.farMax.toMillis() * (1 + pollInterval.jitterRatio) < tokenTtl.toSeconds() * MILLIS_PER_SECOND) {
            "pollInterval.farMax는 지터를 더해도 tokenTtl보다 짧아야 합니다: farMax=${pollInterval.farMax}, tokenTtl=$tokenTtl"
        }
        require(nearRankThreshold > 0) { "nearRankThreshold는 양수여야 합니다: $nearRankThreshold" }
        require(scheduleCacheTtl.isAtLeastOneSecond()) { "scheduleCacheTtl은 1초 이상이어야 합니다: $scheduleCacheTtl" }
        require(!broadcastCacheTtl.isNegative) { "broadcastCacheTtl은 음수일 수 없습니다: $broadcastCacheTtl" }
        require(registerLimit.maxPerIp > 0) { "registerLimit.maxPerIp는 양수여야 합니다: ${registerLimit.maxPerIp}" }
        require(registerLimit.window.isAtLeastOneSecond()) { "registerLimit.window는 1초 이상이어야 합니다: ${registerLimit.window}" }
    }
}

private const val MILLIS_PER_SECOND = 1_000L
private const val DEFAULT_MAX_PER_IP = 100

private fun Duration.isPositive(): Boolean = !isNegative && !isZero

// Redis EX는 초 단위라 1초 미만은 0으로 잘려 SET이 거절된다
private fun Duration.isAtLeastOneSecond(): Boolean = this >= Duration.ofSeconds(1)
