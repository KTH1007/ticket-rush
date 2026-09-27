package com.ticketrush.outbox.application

import com.ticketrush.outbox.domain.OutboxEvent
import com.ticketrush.outbox.domain.OutboxRepositoryPort
import com.ticketrush.outbox.domain.OutboxStatus
import com.ticketrush.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import kotlin.test.Test

class OutboxReclaimSchedulerTest : IntegrationTest() {
    @Autowired
    lateinit var outboxRepository: OutboxRepositoryPort

    @Autowired
    lateinit var reclaimScheduler: OutboxReclaimScheduler

    private val now = LocalDateTime.of(2026, 1, 1, 0, 0)

    @Test
    @Transactional
    fun `claimed_at이 기준보다 오래된 PROCESSING 행을 PENDING으로 되돌린다`() {
        // given
        val event =
            outboxRepository.save(
                OutboxEvent(
                    aggregateType = "RESERVATION",
                    aggregateId = 1L,
                    eventType = "RESERVATION_PAID",
                    payload = "{}",
                    nextAttemptAt = now,
                    createdAt = now,
                ),
            )
        outboxRepository.claimBatch(limit = 10, now = now) // PROCESSING으로 만듦

        // when
        reclaimScheduler.reclaim(staleBefore = now.plusSeconds(1))

        // then
        assertThat(outboxRepository.findById(event.id)?.status).isEqualTo(OutboxStatus.PENDING)
    }
}
