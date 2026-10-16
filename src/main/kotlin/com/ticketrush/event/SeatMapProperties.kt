package com.ticketrush.event

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

// 좌석 상태 스냅샷의 갱신 정책. 기동 때 검증해서 잘못된 값이 요청 처리 중에야 드러나지 않게 한다
@ConfigurationProperties(prefix = "ticket-rush.seat-map")
data class SeatMapProperties(
    val statusRefreshInterval: Duration = Duration.ofSeconds(1),
    // 이 시간 안에 읽힌 공연만 갱신한다
    val statusActiveWindow: Duration = Duration.ofSeconds(10),
    // 이보다 오래된 스냅샷은 읽는 쪽이 새로 만든다
    val statusMaxStale: Duration = Duration.ofSeconds(5),
    val statusEvictAfter: Duration = Duration.ofMinutes(1),
    // 좌석 배치는 거의 안 바뀌어서 길게 둔다. 이 시간 동안 안 읽힌 배치는 메모리에서 지운다
    val layoutTtl: Duration = Duration.ofMinutes(10),
    // 응답의 Cache-Control max-age. 길면 사용자가 그만큼 오래된 값을 본다
    val statusCacheMaxAge: Duration = Duration.ofSeconds(1),
    val layoutCacheMaxAge: Duration = Duration.ofSeconds(60),
    // 배치를 다시 만들지 못한 뒤 같은 공연을 다시 시도하기 전 대기
    val layoutRetryBackoff: Duration = Duration.ofSeconds(5),
) {
    init {
        require(statusRefreshInterval.isPositive()) { "statusRefreshInterval은 양수여야 합니다: $statusRefreshInterval" }
        require(statusActiveWindow.isPositive()) { "statusActiveWindow는 양수여야 합니다: $statusActiveWindow" }
        require(statusMaxStale.isPositive()) { "statusMaxStale은 양수여야 합니다: $statusMaxStale" }
        require(statusEvictAfter.isPositive()) { "statusEvictAfter는 양수여야 합니다: $statusEvictAfter" }
        require(layoutTtl.isPositive()) { "layoutTtl은 양수여야 합니다: $layoutTtl" }
        require(statusCacheMaxAge.isPositive()) { "statusCacheMaxAge는 양수여야 합니다: $statusCacheMaxAge" }
        require(layoutCacheMaxAge.isPositive()) { "layoutCacheMaxAge는 양수여야 합니다: $layoutCacheMaxAge" }
        require(layoutRetryBackoff.isPositive()) { "layoutRetryBackoff는 양수여야 합니다: $layoutRetryBackoff" }
        // 허용 시간이 갱신 주기보다 짧으면 갱신을 기다리는 동안 읽는 요청이 매번 직접 만들게 된다
        require(statusMaxStale >= statusRefreshInterval) {
            "statusMaxStale은 statusRefreshInterval보다 짧을 수 없습니다: maxStale=$statusMaxStale, refreshInterval=$statusRefreshInterval"
        }
        // 활성 구간이 끝나기도 전에 지우면 읽는 중인 공연의 값이 사라진다
        require(statusEvictAfter >= statusActiveWindow) {
            "statusEvictAfter는 statusActiveWindow보다 짧을 수 없습니다: evictAfter=$statusEvictAfter, activeWindow=$statusActiveWindow"
        }
    }
}
