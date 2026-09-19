package com.ticketrush.outbox.infrastructure

import com.ticketrush.outbox.domain.OutboxEvent
import com.ticketrush.outbox.domain.OutboxRepositoryPort
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Repository
class OutboxRepositoryAdapter(
    private val jpaRepository: OutboxJpaRepository,
) : OutboxRepositoryPort {
    override fun save(event: OutboxEvent): OutboxEvent = jpaRepository.saveAndFlush(event)

    override fun findById(id: Long): OutboxEvent? = jpaRepository.findById(id).orElse(null)

    // findClaimableIds(SELECT ... FOR UPDATE SKIP LOCKED)와 markProcessing(UPDATE)이
    // 반드시 같은 트랜잭션 안에서 실행돼야 SKIP LOCKED로 잡은 락이 UPDATE까지 유지된다.
    // 호출하는 쪽의 트랜잭션 유무와 무관하게 이 원자성이 항상 보장돼야 하므로 여기 직접 건다.
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

    override fun reclaimStale(staleBefore: LocalDateTime): Int = jpaRepository.reclaimStale(staleBefore)
}
