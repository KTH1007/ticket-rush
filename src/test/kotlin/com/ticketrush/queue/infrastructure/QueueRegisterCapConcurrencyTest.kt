package com.ticketrush.queue.infrastructure

import com.ticketrush.queue.QueuePolicyProperties
import com.ticketrush.queue.domain.EventSchedule
import com.ticketrush.queue.domain.RegisterResult
import com.ticketrush.support.IntegrationTest
import com.ticketrush.support.testClientIp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// 상한 확인과 순번 부여가 한 스크립트 안에 있어서, 동시에 몰려도 상한을 넘겨 받는 일이 없어야 한다
@Tag("concurrency")
class QueueRegisterCapConcurrencyTest : IntegrationTest() {
    @Autowired
    lateinit var redisTemplate: StringRedisTemplate

    @Autowired
    lateinit var policy: QueuePolicyProperties

    private val schedule = EventSchedule(opensAt = Instant.EPOCH, startsAt = Instant.parse("9999-01-01T00:00:00Z"))

    private fun 동시에_등록(
        repository: QueueRedisAdapter,
        eventId: Long,
        requests: Int,
    ): List<RegisterResult> {
        val ready = CountDownLatch(requests)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(requests)
        return try {
            val futures =
                (1..requests).map {
                    pool.submit<RegisterResult> {
                        ready.countDown()
                        start.await()
                        repository.register(eventId, UUID.randomUUID().toString(), testClientIp, schedule)
                    }
                }
            ready.await()
            start.countDown()
            futures.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    @RepeatedTest(5)
    fun `상한보다 많이 동시에 등록해도 정확히 상한만큼만 통과한다`() {
        // given
        val cap = 100L
        val requests = 300
        val eventId = System.nanoTime()
        val repository = QueueRedisAdapter(redisTemplate, policy.copy(maxWaiting = cap), "queue:test-cap:${UUID.randomUUID()}:")

        // when
        val results = 동시에_등록(repository, eventId, requests)

        // then
        assertThat(results.filterIsInstance<RegisterResult.Registered>()).hasSize(cap.toInt())
        assertThat(results.filter { it == RegisterResult.Full }).hasSize(requests - cap.toInt())
        assertThat(redisTemplate.opsForZSet().zCard("queue:$eventId:waiting")).isEqualTo(cap)
        assertThat(redisTemplate.opsForValue().get("queue:$eventId:seq")).isEqualTo(cap.toString())
    }
}
