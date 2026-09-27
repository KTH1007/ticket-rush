package com.ticketrush.outbox.application

import com.ticketrush.outbox.OutboxPolicyProperties
import com.ticketrush.outbox.domain.OutboxRepositoryPort
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDateTime

private val logger = KotlinLogging.logger {}

// 릴레이 처리 도중 인스턴스가 죽으면 그 행이 PROCESSING에 영구히 묶인다. 오래된 행을 되돌려 다시 처리되게 한다.
@Component
class OutboxReclaimScheduler(
    private val outboxRepository: OutboxRepositoryPort,
    private val policy: OutboxPolicyProperties,
    private val clock: Clock,
) {
    @Scheduled(fixedRateString = "\${ticket-rush.outbox.reclaim-interval}")
    fun reclaim() {
        reclaim(LocalDateTime.now(clock).minus(policy.staleClaimTimeout))
    }

    fun reclaim(staleBefore: LocalDateTime) {
        val reclaimed = outboxRepository.reclaimStale(staleBefore)
        if (reclaimed > 0) {
            logger.warn { "outbox PROCESSING 행 $reclaimed 건을 회수했습니다(staleBefore=$staleBefore)" }
        }
    }
}
