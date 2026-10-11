package com.ticketrush.event.application

import com.ticketrush.event.SeatMapProperties
import org.springframework.scheduling.Trigger
import org.springframework.scheduling.TriggerContext
import org.springframework.scheduling.annotation.SchedulingConfigurer
import org.springframework.scheduling.config.ScheduledTaskRegistrar
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ThreadLocalRandom

@Component
class SeatMapRefresher(
    private val statusCache: SeatStatusSnapshotCache,
    private val layoutCache: SeatLayoutCache,
    private val props: SeatMapProperties,
) : SchedulingConfigurer {
    // 다음 실행을 앞 실행이 끝난 뒤 주기의 90~110% 뒤로 잡는다. 앞 갱신이 끝나기 전에 겹치지 않고(fixedDelay와 같다),
    // 인스턴스끼리 갱신 시각이 흩어지도록 지터를 임계값이 아니라 틱 간격에 넣는다
    internal val trigger = Trigger { context -> nextExecution(context) }

    private fun nextExecution(context: TriggerContext): Instant {
        val base = context.lastCompletion() ?: context.clock.instant()
        val factor = ThreadLocalRandom.current().nextDouble(MIN_FACTOR, MAX_FACTOR)
        return base.plusMillis((props.statusRefreshInterval.toMillis() * factor).toLong())
    }

    override fun configureTasks(registrar: ScheduledTaskRegistrar) {
        registrar.addTriggerTask(::refresh, trigger)
    }

    fun refresh() {
        statusCache.refreshRecentlyRead()
        layoutCache.evictIdle()
    }

    private companion object {
        const val MIN_FACTOR = 0.9
        const val MAX_FACTOR = 1.1
    }
}
