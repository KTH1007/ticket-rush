package com.ticketrush.outbox.infrastructure

import com.ticketrush.outbox.domain.OutboxEvent
import com.ticketrush.outbox.domain.OutboxRepositoryPort
import com.ticketrush.outbox.domain.OutboxStatus
import com.ticketrush.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.LocalDateTime
import kotlin.test.Test

class OutboxRepositoryAdapterTest : IntegrationTest() {
    @Autowired
    lateinit var outboxRepository: OutboxRepositoryPort

    private val now = LocalDateTime.of(2026, 1, 1, 0, 0)

    @Test
    @Transactional
    fun `claimBatch는 PENDING이고 시각이 지난 행만 limit개까지 가져와 PROCESSING으로 바꾼다`() {
        // given
        val ready = outboxRepository.save(이벤트(aggregateId = 1L, nextAttemptAt = now))
        val notYet = outboxRepository.save(이벤트(aggregateId = 2L, nextAttemptAt = now.plusMinutes(10)))

        // when
        val claimed = outboxRepository.claimBatch(limit = 10, now = now)

        // then
        assertThat(claimed.map { it.id }).containsExactly(ready.id)
        assertThat(claimed.single().status).isEqualTo(OutboxStatus.PROCESSING)
        assertThat(outboxRepository.findById(notYet.id)?.status).isEqualTo(OutboxStatus.PENDING)
    }

    @Test
    @Transactional
    fun `claimBatch는 limit을 넘겨 가져오지 않는다`() {
        // given
        repeat(3) { outboxRepository.save(이벤트(aggregateId = it.toLong(), nextAttemptAt = now)) }

        // when
        val claimed = outboxRepository.claimBatch(limit = 2, now = now)

        // then
        assertThat(claimed).hasSize(2)
    }

    @Test
    @Transactional
    fun `reclaimStale은 claimed_at이 기준보다 오래된 PROCESSING 행만 PENDING으로 되돌린다`() {
        // given
        val stale = outboxRepository.save(이벤트(aggregateId = 1L, nextAttemptAt = now))
        outboxRepository.claimBatch(limit = 10, now = now) // stale을 PROCESSING으로 만듦

        // when
        val reclaimed = outboxRepository.reclaimStale(staleBefore = now.plusSeconds(1))

        // then
        assertThat(reclaimed).isEqualTo(1)
        assertThat(outboxRepository.findById(stale.id)?.status).isEqualTo(OutboxStatus.PENDING)
    }

    @Test
    @Transactional
    fun `markDone은 PROCESSING 행을 DONE으로 바꾼다`() {
        // given
        outboxRepository.save(이벤트(aggregateId = 1L, nextAttemptAt = now))
        val claimed = outboxRepository.claimBatch(limit = 10, now = now).single()

        // when
        outboxRepository.markDone(claimed.id, requireNotNull(claimed.claimedAt))

        // then
        assertThat(outboxRepository.findById(claimed.id)?.status).isEqualTo(OutboxStatus.DONE)
    }

    @Test
    @Transactional
    fun `claimedAt이 일치하지 않으면(소유권을 잃으면) markDone을 건너뛴다`() {
        // given
        outboxRepository.save(이벤트(aggregateId = 1L, nextAttemptAt = now))
        val claimed = outboxRepository.claimBatch(limit = 10, now = now).single()
        val staleClaimedAt = requireNotNull(claimed.claimedAt).minusMinutes(1)

        // when
        outboxRepository.markDone(claimed.id, staleClaimedAt)

        // then
        assertThat(outboxRepository.findById(claimed.id)?.status).isEqualTo(OutboxStatus.PROCESSING)
    }

    @Test
    @Transactional
    fun `markFailedOrRetry는 attempt_count를 늘리고 임계치 미만이면 PENDING으로 되돌린다`() {
        // given
        outboxRepository.save(이벤트(aggregateId = 1L, nextAttemptAt = now))
        val claimed = outboxRepository.claimBatch(limit = 10, now = now).single()

        // when
        outboxRepository.markFailedOrRetry(claimed.id, requireNotNull(claimed.claimedAt), now, Duration.ofSeconds(30), maxAttempts = 5)

        // then
        val updated = outboxRepository.findById(claimed.id)
        assertThat(updated?.attemptCount).isEqualTo(1)
        assertThat(updated?.status).isEqualTo(OutboxStatus.PENDING)
        assertThat(updated?.nextAttemptAt).isEqualTo(now.plusSeconds(30))
    }

    @Test
    @Transactional
    fun `claimedAt이 일치하지 않으면(소유권을 잃으면) markFailedOrRetry를 건너뛴다`() {
        // given
        outboxRepository.save(이벤트(aggregateId = 1L, nextAttemptAt = now))
        val claimed = outboxRepository.claimBatch(limit = 10, now = now).single()
        val staleClaimedAt = requireNotNull(claimed.claimedAt).minusMinutes(1)

        // when
        outboxRepository.markFailedOrRetry(claimed.id, staleClaimedAt, now, Duration.ofSeconds(30), maxAttempts = 5)

        // then
        val untouched = outboxRepository.findById(claimed.id)
        assertThat(untouched?.status).isEqualTo(OutboxStatus.PROCESSING)
        assertThat(untouched?.attemptCount).isEqualTo(0)
    }

    private fun 이벤트(
        aggregateId: Long,
        nextAttemptAt: LocalDateTime,
    ): OutboxEvent =
        OutboxEvent(
            aggregateType = "RESERVATION",
            aggregateId = aggregateId,
            eventType = "RESERVATION_PAID",
            payload = """{"reservationId":$aggregateId}""",
            nextAttemptAt = nextAttemptAt,
            createdAt = now,
        )
}
