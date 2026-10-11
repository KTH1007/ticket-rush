package com.ticketrush.event.application

import com.ticketrush.event.SeatMapProperties
import com.ticketrush.support.TestClock
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.springframework.scheduling.support.SimpleTriggerContext
import java.time.Duration
import kotlin.test.Test

class SeatMapRefresherTest {
    private val statusCache = mockk<SeatStatusSnapshotCache>(relaxed = true)
    private val layoutCache = mockk<SeatLayoutCache>(relaxed = true)
    private val clock = TestClock()
    private val interval = Duration.ofSeconds(1)
    private val refresher = SeatMapRefresher(statusCache, layoutCache, SeatMapProperties(statusRefreshInterval = interval))

    @Test
    fun `틱마다 상태 갱신과 배치 정리를 함께 한다`() {
        refresher.refresh()

        verify(exactly = 1) { statusCache.refreshRecentlyRead() }
        verify(exactly = 1) { layoutCache.evictIdle() }
    }

    // 지터는 갱신 임계값이 아니라 틱 간격에 넣어야 인스턴스끼리 흩어지면서도 갱신이 매 틱 돈다
    @Test
    fun `다음 실행은 끝난 시각에서 갱신 주기의 90퍼센트에서 110퍼센트 사이 뒤다`() {
        val completedAt = clock.instant()
        val context = SimpleTriggerContext(clock).apply { update(completedAt, completedAt, completedAt) }

        val delays = (1..1_000).map { Duration.between(completedAt, refresher.trigger.nextExecution(context)!!).toMillis() }

        assertThat(delays).allMatch { it in 900L..1_100L }
        assertThat(delays.toSet().size).isGreaterThan(1)
    }

    @Test
    fun `아직 한 번도 실행하지 않았으면 지금부터 갱신 주기의 90퍼센트에서 110퍼센트 사이 뒤다`() {
        val context = SimpleTriggerContext(clock)

        val next = refresher.trigger.nextExecution(context)!!

        assertThat(Duration.between(clock.instant(), next).toMillis()).isBetween(900L, 1_100L)
    }
}
