package com.ticketrush.queue.infrastructure

import com.ticketrush.queue.QueuePolicyProperties
import com.ticketrush.support.IntegrationTest
import com.ticketrush.support.열린_공연에_등록
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.connection.RedisConnection
import org.springframework.data.redis.core.RedisCallback
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test

// 모든 테스트가 같은 Redis의 초 단위 키를 같이 쓰므로, 이 테스트는 고유 접두사의 예산 키만 본다
class QueuePromotionBudgetTest : IntegrationTest() {
    @Autowired
    lateinit var redisTemplate: StringRedisTemplate

    @Autowired
    lateinit var policy: QueuePolicyProperties

    private val budget = 5
    private lateinit var prefix: String
    private lateinit var repository: QueueRedisAdapter

    @BeforeEach
    fun setUp() {
        prefix = "queue:test-budget:${UUID.randomUUID()}:"
        repository = QueueRedisAdapter(redisTemplate, policy.copy(promotionBudgetPerSecond = budget), prefix)
    }

    private fun 대기자_등록(
        eventId: Long,
        count: Int,
    ) = repeat(count) { repository.열린_공연에_등록(eventId, UUID.randomUUID().toString()) }

    private fun 초별_사용량(): List<Long> = redisTemplate.keys("$prefix*").map { redisTemplate.opsForValue().get(it)!!.toLong() }

    private fun redisSecond(): Long {
        val millis =
            redisTemplate.execute(
                RedisCallback { connection: RedisConnection -> connection.serverCommands().time(TimeUnit.MILLISECONDS) },
            )
        return millis!! / 1000
    }

    private fun 동시에_승격(events: List<Long>): Int {
        val ready = CountDownLatch(events.size)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(events.size)
        val futures =
            events.map { eventId ->
                pool.submit<Int> {
                    ready.countDown()
                    start.await()
                    repository.promote(eventId, 100).size
                }
            }
        ready.await()
        start.countDown()
        return futures.sumOf { it.get(10, TimeUnit.SECONDS) }.also { pool.shutdown() }
    }

    @Test
    fun `같은 초에 여러 공연이 요청해도 초마다 승격 합계가 예산을 넘지 않는다`() {
        // given
        val events = (0L..2L).map { System.nanoTime() + it }
        events.forEach { 대기자_등록(it, 10) }

        // when
        val promoted = events.sumOf { repository.promote(it, 100).size }

        // then
        val usage = 초별_사용량()
        assertThat(usage).allMatch { it <= budget }
        assertThat(usage.sum()).isEqualTo(promoted.toLong())
        assertThat(usage.max()).isEqualTo(budget.toLong())
    }

    // 예산을 공연마다 먼저 통째로 예약하던 때는 대기자가 1명인 공연이 예산을 가져가 뒤의 공연이 0을 받았다
    @Test
    fun `대기자가 적은 공연이 예산을 독점하지 않는다`() {
        // given
        val small = System.nanoTime()
        val large = small + 1
        대기자_등록(small, 1)
        대기자_등록(large, 10)

        // when
        val smallPromoted = repository.promote(small, 100)
        val largePromoted = repository.promote(large, 100)

        // then
        assertThat(smallPromoted).hasSize(1)
        assertThat(largePromoted.size).isGreaterThanOrEqualTo(budget - 1) // 두 호출 사이에 초가 넘어가면 budget만큼
    }

    @Test
    fun `초가 바뀌면 새 카운터로 시작한다`() {
        // given
        val eventId = System.nanoTime()
        대기자_등록(eventId, budget * 3)
        assertThat(repository.promote(eventId, 100)).hasSize(budget)
        val secondAfterFirst = redisSecond()

        // when
        while (redisSecond() <= secondAfterFirst) Thread.sleep(20)

        // then
        assertThat(repository.promote(eventId, 100)).hasSize(budget)
    }

    @Tag("concurrency")
    @Test
    fun `여러 스레드가 동시에 요청해도 초마다 승격 합계가 예산을 넘지 않는다`() {
        // given
        val events = (0L..7L).map { System.nanoTime() + it }
        events.forEach { 대기자_등록(it, 10) }

        // when
        val promoted = 동시에_승격(events)

        // then
        val usage = 초별_사용량()
        assertThat(usage).allMatch { it <= budget }
        assertThat(usage.sum()).isEqualTo(promoted.toLong())
    }
}
