package com.ticketrush.shared.config

import com.ticketrush.shared.AppTimeZone
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class ClockConfig {
    // LocalDateTime.now()를 직접 호출하지 않기 위해 Clock을 빈으로 둠. 호스트 존을 따르면 비KST 호스트에서 DB 저장 시각과 비교가 어긋남
    @Bean
    fun clock(): Clock = Clock.system(AppTimeZone.ID)
}
