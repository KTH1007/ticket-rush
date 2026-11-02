package com.ticketrush.queue.application

import com.ticketrush.queue.QueuePolicyProperties
import com.ticketrush.queue.domain.EventSchedulePort
import com.ticketrush.queue.domain.QueueRepositoryPort
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

private val logger = KotlinLogging.logger {}

// 공연이 시작하고 유예 시간이 지나면 남은 대기열 키를 지운다. 대상은 활성 공연 집합에 남은 공연이다(매진으로 승격이 멈춘 공연 포함).
// 대기열이 비어 집합에서 빠진 공연은 승격 스크립트가 순번 키에 만료를 걸어 둔다
@Component
class QueueCleanupScheduler(
    private val queueRepository: QueueRepositoryPort,
    private val eventSchedulePort: EventSchedulePort,
    private val policy: QueuePolicyProperties,
    private val clock: Clock,
) {
    @Scheduled(
        fixedRateString = "\${ticket-rush.queue.cleanup.interval}",
        initialDelayString = "\${ticket-rush.queue.cleanup.interval}",
    )
    fun cleanup() {
        val now = Instant.now(clock)
        queueRepository.activeEventIds().forEach { eventId ->
            // 한 공연의 실패가 다른 공연의 정리를 막지 않게 하고, 실패는 다음 주기에 다시 시도된다
            runCatching { cleanup(eventId, now) }
                .onFailure { logger.warn(it) { "공연 $eventId 의 대기열 정리에 실패했습니다" } }
        }
    }

    private fun cleanup(
        eventId: Long,
        now: Instant,
    ) {
        val startsAt = eventSchedulePort.schedule(eventId)?.startsAt ?: return
        val notBefore = startsAt.plus(policy.cleanup.graceAfterStart)
        if (now.isBefore(notBefore)) return
        if (queueRepository.cleanup(eventId, notBefore)) {
            logger.info { "공연 $eventId 의 대기열 키를 정리했습니다(notBefore=$notBefore)" }
        }
    }
}
