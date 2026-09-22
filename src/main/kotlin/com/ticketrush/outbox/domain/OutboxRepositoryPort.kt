package com.ticketrush.outbox.domain

import java.time.Duration
import java.time.LocalDateTime

interface OutboxRepositoryPort {
    fun save(event: OutboxEvent): OutboxEvent

    fun findById(id: Long): OutboxEvent?

    // 상태를 안 바꾸는 순수 조회. claimBatch(부수효과 있음)를 검증용으로 재사용하면 안 된다.
    fun findByAggregateId(aggregateId: Long): List<OutboxEvent>

    // SKIP LOCKED로 PENDING 행을 최대 limit개 claim해서 PROCESSING으로 표시하고 반환한다.
    fun claimBatch(
        limit: Int,
        now: LocalDateTime,
    ): List<OutboxEvent>

    // claimed_at이 staleBefore보다 오래된 PROCESSING 행을 PENDING으로 되돌린다. 되돌린 행 수를 반환한다.
    fun reclaimStale(staleBefore: LocalDateTime): Int

    // 스케줄러의 self-invocation으로 @Transactional이 무시되는 걸 피하려 여기 둠.
    fun markDone(id: Long)

    fun markFailedOrRetry(
        id: Long,
        now: LocalDateTime,
        retryDelay: Duration,
        maxAttempts: Int,
    )
}
