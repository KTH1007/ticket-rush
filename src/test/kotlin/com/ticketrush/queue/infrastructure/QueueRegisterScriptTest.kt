package com.ticketrush.queue.infrastructure

import com.ticketrush.queue.QueuePolicyProperties
import com.ticketrush.support.IntegrationTest
import com.ticketrush.support.열린_공연에_등록
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test

class QueueRegisterScriptTest : IntegrationTest() {
    @Autowired
    lateinit var redisTemplate: StringRedisTemplate

    @Autowired
    lateinit var repository: QueueRedisAdapter

    @Autowired
    lateinit var policy: QueuePolicyProperties

    private val eventId = System.nanoTime()

    @Test
    fun `등록하면 token 키에 token-ttl이 걸린다`() {
        // given
        val token = UUID.randomUUID().toString()

        // when
        repository.열린_공연에_등록(eventId, token)

        // then
        val ttl = redisTemplate.getExpire("queue:$eventId:token:$token")!!
        assertThat(ttl).isBetween(policy.tokenTtl.toSeconds() - 5, policy.tokenTtl.toSeconds())
    }

    @Tag("concurrency")
    @Test
    fun `동시에 등록해도 순번이 겹치지 않고 빠짐이 없다`() {
        // given
        val count = 200

        // when
        val sequences = 동시에_등록(count)

        // then
        assertThat(sequences).containsExactlyInAnyOrderElementsOf((1L..count).toList())
    }

    private fun 동시에_등록(count: Int): List<Long> {
        val ready = CountDownLatch(count)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(count)
        return try {
            val futures =
                (1..count).map {
                    pool.submit<Long> {
                        ready.countDown()
                        start.await()
                        repository.열린_공연에_등록(eventId, UUID.randomUUID().toString())
                    }
                }
            ready.await()
            start.countDown()
            futures.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }
}
