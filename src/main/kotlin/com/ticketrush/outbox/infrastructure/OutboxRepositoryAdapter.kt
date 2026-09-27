package com.ticketrush.outbox.infrastructure

import com.ticketrush.outbox.domain.OutboxEvent
import com.ticketrush.outbox.domain.OutboxRepositoryPort
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.LocalDateTime

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
    override fun markDone(id: Long) {
        jpaRepository.findById(id).ifPresent {
            it.markDone()
            jpaRepository.save(it)
        }
    }

    @Transactional
    override fun markFailedOrRetry(
        id: Long,
        now: LocalDateTime,
        retryDelay: Duration,
        maxAttempts: Int,
    ) {
        jpaRepository.findById(id).ifPresent {
            it.recordFailure(now, retryDelay, maxAttempts)
            jpaRepository.save(it)
        }
    }
}
