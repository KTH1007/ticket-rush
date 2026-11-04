package com.ticketrush.shared.warmup

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

// 기동 직후 예열 설정. 기본은 꺼 두고 컨테이너 프로필에서 켠다
@ConfigurationProperties(prefix = "ticket-rush.warmup")
data class WarmupProperties(
    val enabled: Boolean = false,
    // 이 시간 동안 읽기 경로를 반복해서 부른다. readiness는 이 시간만큼 늦게 UP이 된다
    val duration: Duration = Duration.ofSeconds(20),
    val concurrency: Int = 8,
) {
    init {
        require(duration.isPositive()) { "duration은 양수여야 합니다: $duration" }
        require(concurrency > 0) { "concurrency는 1 이상이어야 합니다: $concurrency" }
    }
}
