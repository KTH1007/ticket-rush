package com.ticketrush.outbox.infrastructure

import com.ticketrush.outbox.domain.OutboxEvent
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface OutboxJpaRepository : JpaRepository<OutboxEvent, Long> {
    fun findByAggregateId(aggregateId: Long): List<OutboxEvent>

    // 이미 잠긴 행은 SKIP LOCKED로 건너뛰어 여러 인스턴스가 겹치지 않게 한다.
    @Query(
        value = """
            SELECT id FROM outbox
            WHERE status = 'PENDING' AND next_attempt_at <= :now
            ORDER BY next_attempt_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
        """,
        nativeQuery = true,
    )
    fun findClaimableIds(
        @Param("now") now: LocalDateTime,
        @Param("limit") limit: Int,
    ): List<Long>

    // clearAutomatically 없으면 bulk UPDATE 후 findAllById가 캐시된 값을 돌려줌(실측 확인).
    @Modifying(clearAutomatically = true)
    @Query(
        "UPDATE OutboxEvent o SET o.status = com.ticketrush.outbox.domain.OutboxStatus.PROCESSING, o.claimedAt = :now " +
            "WHERE o.id IN :ids",
    )
    fun markProcessing(
        @Param("ids") ids: List<Long>,
        @Param("now") now: LocalDateTime,
    ): Int

    @Modifying(clearAutomatically = true)
    @Query(
        "UPDATE OutboxEvent o SET o.status = com.ticketrush.outbox.domain.OutboxStatus.PENDING, o.claimedAt = null " +
            "WHERE o.status = com.ticketrush.outbox.domain.OutboxStatus.PROCESSING AND o.claimedAt < :staleBefore",
    )
    fun reclaimStale(
        @Param("staleBefore") staleBefore: LocalDateTime,
    ): Int
}
