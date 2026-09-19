package com.ticketrush.outbox.infrastructure

import com.ticketrush.outbox.domain.OutboxEvent
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface OutboxJpaRepository : JpaRepository<OutboxEvent, Long> {
    // SKIP LOCKED로 이미 잠긴 행은 건너뛴다. 이 락은 같은 트랜잭션 안에서 뒤이은
    // markProcessing UPDATE까지 유지되므로, 여러 인스턴스가 동시에 호출해도 겹치지 않는다.
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

    // clearAutomatically = true: bulk UPDATE는 DB엔 반영되지만 이미 영속성 컨텍스트에 올라온
    // 엔티티(예: 방금 save()한 것)는 자동 갱신이 안 된다. 이걸 켜야 이어지는 findAllById가
    // 캐시된 값 대신 DB에서 새로 읽어온다(실측으로 확인한 필요성 — 처음엔 없이 짰다가 실패함).
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
