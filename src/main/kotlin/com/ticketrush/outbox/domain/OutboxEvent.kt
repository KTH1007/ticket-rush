package com.ticketrush.outbox.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Duration
import java.time.LocalDateTime

@Entity
@Table(name = "outbox")
class OutboxEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    aggregateType: String,
    aggregateId: Long,
    eventType: String,
    payload: String,
    status: OutboxStatus = OutboxStatus.PENDING,
    attemptCount: Int = 0,
    nextAttemptAt: LocalDateTime,
    claimedAt: LocalDateTime? = null,
    createdAt: LocalDateTime,
) {
    @Column(name = "aggregate_type", nullable = false, length = 50)
    var aggregateType: String = aggregateType
        protected set

    @Column(name = "aggregate_id", nullable = false)
    var aggregateId: Long = aggregateId
        protected set

    @Column(name = "event_type", nullable = false, length = 50)
    var eventType: String = eventType
        protected set

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    var payload: String = payload
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: OutboxStatus = status
        protected set

    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int = attemptCount
        protected set

    @Column(name = "next_attempt_at", nullable = false)
    var nextAttemptAt: LocalDateTime = nextAttemptAt
        protected set

    @Column(name = "claimed_at")
    var claimedAt: LocalDateTime? = claimedAt
        protected set

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = createdAt

    fun markDone() {
        check(status == OutboxStatus.PROCESSING) { "PROCESSING 상태에서만 완료 처리할 수 있습니다: $status" }
        status = OutboxStatus.DONE
    }

    fun recordFailure(
        now: LocalDateTime,
        retryDelay: Duration,
        maxAttempts: Int,
    ) {
        check(status == OutboxStatus.PROCESSING) { "PROCESSING 상태에서만 실패를 기록할 수 있습니다: $status" }
        attemptCount += 1
        status = if (attemptCount >= maxAttempts) OutboxStatus.FAILED else OutboxStatus.PENDING
        nextAttemptAt = now.plus(retryDelay)
    }

    // 테스트에서 claim을 거치지 않고 PROCESSING 상태를 직접 만들기 위한 것. 프로덕션 경로는
    // 항상 리포지토리의 claimBatch(SKIP LOCKED)를 거쳐 PROCESSING이 된다.
    internal fun markProcessingForTest() {
        status = OutboxStatus.PROCESSING
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OutboxEvent) return false
        return id != 0L && id == other.id
    }

    override fun hashCode(): Int = OutboxEvent::class.java.hashCode()
}
