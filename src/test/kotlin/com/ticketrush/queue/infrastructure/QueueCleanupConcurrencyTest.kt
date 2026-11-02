package com.ticketrush.queue.infrastructure

import com.ticketrush.queue.domain.EventSchedule
import com.ticketrush.support.IntegrationTest
import com.ticketrush.support.testClientIp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test

// 정리와 등록, 승격이 겹쳐도 줄이 남아 있는 동안은 순번 키와 활성 집합이 함께 있어야 한다.
// 관찰자도 Lua로 한 번에 읽는다. 클라이언트가 키를 따로 읽으면 서버는 맞는데도 사이에 상태가 바뀐 것을 위반으로 오해한다.
// 정리 뒤의 등록은 닫힌 것으로 거절되므로 겹침은 첫 정리 순간뿐이다. 그래서 공연을 여러 번 바꿔 가며 겹치게 한다
@Tag("concurrency")
class QueueCleanupConcurrencyTest : IntegrationTest() {
    @Autowired
    lateinit var redisTemplate: StringRedisTemplate

    @Autowired
    lateinit var repository: QueueRedisAdapter

    private val schedule = EventSchedule(opensAt = Instant.EPOCH, startsAt = Instant.parse("9999-01-01T00:00:00Z"))

    // KEYS = waiting, seq, 활성 집합, lastPromotedSeq / ARGV = eventId. 위반이면 1
    private val observer =
        DefaultRedisScript<Long>(
            """
            if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end
            if redis.call('EXISTS', KEYS[2]) == 0 then return 1 end
            if redis.call('SISMEMBER', KEYS[3], ARGV[1]) == 0 then return 1 end
            if redis.call('TTL', KEYS[2]) ~= -1 then return 1 end
            if redis.call('TTL', KEYS[4]) > 0 then return 1 end
            return 0
            """.trimIndent(),
            Long::class.java,
        )

    // 정리 뒤에는 Closed가 돌아오므로 결과는 보지 않는다
    private fun 등록(eventId: Long) {
        repository.register(eventId, UUID.randomUUID().toString(), testClientIp, schedule)
    }

    private fun 위반_수(eventId: Long): Long {
        val keys = listOf("queue:$eventId:waiting", "queue:$eventId:seq", "queue:activeEventIds", "queue:$eventId:lastPromotedSeq")
        return redisTemplate.execute(observer, keys, eventId.toString()) ?: 0L
    }

    private fun 동시에_실행(tasks: List<() -> Unit>) {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(tasks.size)
        val futures =
            tasks.map { task ->
                pool.submit(
                    Runnable {
                        start.await()
                        task()
                    },
                )
            }
        start.countDown()
        futures.forEach { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()
    }

    private fun 한_공연에서_겹치게(violations: AtomicLong) {
        val eventId = System.nanoTime()
        repeat(20) { 등록(eventId) }
        val registrars: List<() -> Unit> = List(6) { { repeat(30) { 등록(eventId) } } }

        동시에_실행(
            registrars +
                listOf(
                    { repository.cleanup(eventId, Instant.EPOCH) },
                    { repeat(30) { repository.promote(eventId, 3) } },
                    { repeat(200) { violations.addAndGet(위반_수(eventId)) } },
                ),
        )

        repository.cleanup(eventId, Instant.EPOCH)
        assertThat(redisTemplate.hasKey("queue:$eventId:waiting")).isFalse()
        assertThat(redisTemplate.hasKey("queue:$eventId:seq")).isFalse()
        assertThat(redisTemplate.hasKey("queue:$eventId:lastPromotedSeq")).isFalse()
        assertThat(repository.activeEventIds()).doesNotContain(eventId)
    }

    @RepeatedTest(5)
    fun `정리와 등록과 승격이 겹쳐도 줄이 있는 동안은 순번 키와 활성 집합이 함께 남는다`() {
        val violations = AtomicLong()

        repeat(10) { 한_공연에서_겹치게(violations) }

        assertThat(violations.get()).isZero()
    }

    @Test
    fun `관찰자는 줄이 있는데 순번 키가 없는 상태를 위반으로 센다`() {
        // given
        val eventId = System.nanoTime()
        등록(eventId)
        assertThat(위반_수(eventId)).isZero()

        // when
        redisTemplate.delete("queue:$eventId:seq")

        // then
        assertThat(위반_수(eventId)).isEqualTo(1L)
    }
}
