package com.ticketrush.shared.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class ClockConfig {
    // LocalDateTime.now()를 직접 호출하지 않기 위해 Clock을 빈으로 둠
    @Bean
    fun clock(): Clock = Clock.systemDefaultZone()
}
