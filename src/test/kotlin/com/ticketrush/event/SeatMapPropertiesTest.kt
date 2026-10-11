package com.ticketrush.event

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.time.Duration
import kotlin.test.Test

class SeatMapPropertiesTest {
    private fun properties(
        refreshInterval: Duration = Duration.ofSeconds(1),
        activeWindow: Duration = Duration.ofSeconds(10),
        maxStale: Duration = Duration.ofSeconds(5),
        evictAfter: Duration = Duration.ofMinutes(1),
    ) = SeatMapProperties(
        statusRefreshInterval = refreshInterval,
        statusActiveWindow = activeWindow,
        statusMaxStale = maxStale,
        statusEvictAfter = evictAfter,
    )

    private fun assertRejected(
        field: String,
        create: () -> SeatMapProperties,
    ) {
        assertThatThrownBy { create() }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining(field)
    }

    @Test
    fun `기본값은 갱신 1초, 활성 10초, 허용 5초, 삭제 1분이다`() {
        val defaults = SeatMapProperties()

        assertThat(defaults.statusRefreshInterval).isEqualTo(Duration.ofSeconds(1))
        assertThat(defaults.statusActiveWindow).isEqualTo(Duration.ofSeconds(10))
        assertThat(defaults.statusMaxStale).isEqualTo(Duration.ofSeconds(5))
        assertThat(defaults.statusEvictAfter).isEqualTo(Duration.ofMinutes(1))
    }

    @Test
    fun `0이나 음수면 거부한다`() {
        assertRejected("statusRefreshInterval") { properties(refreshInterval = Duration.ZERO) }
        assertRejected("statusActiveWindow") { properties(activeWindow = Duration.ofSeconds(-1)) }
        assertRejected("statusMaxStale") { properties(maxStale = Duration.ZERO) }
        assertRejected("statusEvictAfter") { properties(evictAfter = Duration.ZERO) }
    }

    // 허용 시간이 갱신 주기보다 짧으면 갱신을 기다리는 동안 읽는 요청이 매번 직접 만들게 된다
    @Test
    fun `허용 시간이 갱신 주기보다 짧으면 거부한다`() {
        assertRejected("statusMaxStale") { properties(refreshInterval = Duration.ofSeconds(2), maxStale = Duration.ofSeconds(1)) }
    }

    // 활성 구간이 끝나기도 전에 지우면 읽는 중인 공연의 값이 사라진다
    @Test
    fun `삭제 시간이 활성 구간보다 짧으면 거부한다`() {
        assertRejected("statusEvictAfter") { properties(activeWindow = Duration.ofSeconds(30), evictAfter = Duration.ofSeconds(10)) }
    }
}
