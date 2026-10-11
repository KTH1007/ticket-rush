package com.ticketrush.shared

import com.ticketrush.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import kotlin.test.Test

class BaseEntityAuditingClockTest : IntegrationTest() {
    @Autowired
    lateinit var probeRepository: BaseEntityTestProbeRepository

    // 과거로 고정한다. 이 컨텍스트의 스케줄러가 같은 DB의 다른 테스트 행을 만료나 회수 대상으로 보지 않게 하려는 것이다
    @TestConfiguration
    class FixedClockConfig {
        @Bean
        @Primary
        fun fixedClock(): Clock = Clock.fixed(Instant.parse("2020-01-02T03:04:05Z"), AppTimeZone.ID)
    }

    @Test
    fun `감사 시각은 주입된 Clock으로 채워진다`() {
        val saved = probeRepository.saveAndFlush(BaseEntityTestProbe())

        val expected = LocalDateTime.of(2020, 1, 2, 12, 4, 5)
        assertThat(saved.createdAt).isEqualTo(expected)
        assertThat(saved.updatedAt).isEqualTo(expected)
    }
}
