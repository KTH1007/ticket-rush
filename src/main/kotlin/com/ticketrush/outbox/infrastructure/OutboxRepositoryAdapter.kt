package com.ticketrush.outbox.infrastructure

import com.ticketrush.outbox.domain.OutboxEvent
import com.ticketrush.outbox.domain.OutboxRepositoryPort
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.LocalDateTime

private val logger = KotlinLogging.logger {}

@Repository
class OutboxRepositoryAdapter(
    private val jpaRepository: OutboxJpaRepository,
) : OutboxRepositoryPort {
    override fun save(event: OutboxEvent): OutboxEvent = jpaRepository.saveAndFlush(event)

    override fun findById(id: Long): OutboxEvent? = jpaRepository.findById(id).orElse(null)

    override fun findByAggregateId(aggregateId: Long): List<OutboxEvent> = jpaRepository.findByAggregateId(aggregateId)

    // SKIP LOCKED 락이 markProcessing까지 유지되도록 같은 트랜잭션으로 묶는다.
    @Transactional
    override fun claimBatch(
        limit: Int,
        now: LocalDateTime,
    ): List<OutboxEvent> {
        val ids = jpaRepository.findClaimableIds(now, limit)
        if (ids.isEmpty()) return emptyList()
        jpaRepository.markProcessing(ids, now)
        return jpaRepository.findAllById(ids)
    }

    @Transactional
    override fun reclaimStale(staleBefore: LocalDateTime): Int = jpaRepository.reclaimStale(staleBefore)

    @Transactional
    override fun markDone(
        id: Long,
        claimedAt: LocalDateTime,
    ) {
        val event = jpaRepository.findById(id).orElse(null) ?: return
        if (!ownsClaim(event, id, claimedAt, "markDone")) return
        event.markDone()
        jpaRepository.save(event)
    }

    @Transactional
    override fun markFailedOrRetry(
        id: Long,
        claimedAt: LocalDateTime,
        now: LocalDateTime,
        retryDelay: Duration,
        maxAttempts: Int,
    ) {
        val event = jpaRepository.findById(id).orElse(null) ?: return
        if (!ownsClaim(event, id, claimedAt, "markFailedOrRetry")) return
        event.recordFailure(now, retryDelay, maxAttempts)
        jpaRepository.save(event)
    }

    // 처리 도중 회수 스케줄러가 이미 PENDING으로 되돌렸으면(claimedAt 불일치) 상태를 안 건드리고 건너뛴다.
    private fun ownsClaim(
        event: OutboxEvent,
        id: Long,
        claimedAt: LocalDateTime,
        operation: String,
    ): Boolean {
        if (event.claimedAt == claimedAt) return true
        logger.warn { "outbox id=$id 는 claim 소유권을 잃어 $operation 을 건너뜁니다(claimedAt 불일치)" }
        return false
    }
}
