package com.ticketrush.outbox.infrastructure

import com.ticketrush.outbox.domain.OutboxEvent
import com.ticketrush.outbox.domain.OutboxRepositoryPort
import com.ticketrush.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import java.time.Clock
import java.time.LocalDateTime
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// SKIP LOCKED가 실제로 겹치지 않게 나눠주는지 확인한다. 진짜 OutboxRelayScheduler가
// 백그라운드에서 같이 돌면서 행을 가로채는 걸 막기 위해 poll-interval을 길게 늘린다
@Tag("concurrency")
@TestPropertySource(properties = ["ticket-rush.outbox.poll-interval=1h", "ticket-rush.outbox.reclaim-interval=1h"])
class OutboxRepositoryAdapterConcurrencyTest : IntegrationTest() {
    @Autowired
    lateinit var outboxRepository: OutboxRepositoryPort

    @Autowired
    lateinit var clock: Clock

    @RepeatedTest(5)
    fun `여러 스레드가 동시에 claim해도 같은 행을 중복 처리하지 않는다`() {
        // given
        val now = LocalDateTime.now(clock)
        val totalEvents = 50
        val myIds = 이벤트_여러개_저장(totalEvents, now)
        val threadCount = 10

        // when
        val claimedIdLists = 동시_claim(threadCount, limitPerThread = totalEvents, now = now)

        // then
        val myClaimed = claimedIdLists.flatten().filter { it in myIds }
        assertThat(myClaimed).hasSize(totalEvents) // 누락도 중복도 없음
        assertThat(myClaimed.toSet()).hasSize(totalEvents)
    }

    private fun 이벤트_여러개_저장(
        count: Int,
        now: LocalDateTime,
    ): Set<Long> =
        (1..count)
            .map {
                outboxRepository
                    .save(
                        OutboxEvent(
                            aggregateType = "RESERVATION",
                            aggregateId = 900_100_000L + it,
                            eventType = "RESERVATION_PAID",
                            payload = "{}",
                            nextAttemptAt = now,
                            createdAt = now,
                        ),
                    ).id
            }.toSet()

    private fun 동시_claim(
        threadCount: Int,
        limitPerThread: Int,
        now: LocalDateTime,
    ): List<List<Long>> {
        val startGate = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)
        val results = Collections.synchronizedList(mutableListOf<List<Long>>())

        try {
            repeat(threadCount) {
                executor.submit { claim한번(startGate, doneLatch, results, limitPerThread, now) }
            }
            startGate.countDown()
            check(doneLatch.await(10, TimeUnit.SECONDS)) { "claim 스레드가 10초 안에 끝나지 않았다" }
        } finally {
            executor.shutdownNow()
        }
        return results
    }

    private fun claim한번(
        startGate: CountDownLatch,
        doneLatch: CountDownLatch,
        results: MutableList<List<Long>>,
        limit: Int,
        now: LocalDateTime,
    ) {
        startGate.await()
        val claimed = outboxRepository.claimBatch(limit = limit, now = now)
        results.add(claimed.map { it.id })
        doneLatch.countDown()
    }
}
