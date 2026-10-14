package com.ticketrush.queue.infrastructure

import com.ticketrush.queue.domain.BindResult
import com.ticketrush.shared.PhoneHash
import com.ticketrush.support.IntegrationTest
import com.ticketrush.support.열린_공연에_등록
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test

class QueueBindScriptTest : IntegrationTest() {
    @Autowired
    lateinit var redisTemplate: StringRedisTemplate

    @Autowired
    lateinit var repository: QueueRedisAdapter

    private val eventId = System.nanoTime()

    private fun activeKey(token: String) = "queue:$eventId:active:$token"

    private fun phone(seed: Int) = PhoneHash(ByteArray(32) { seed.toByte() })

    private fun 승격된_토큰(): String {
        val token = UUID.randomUUID().toString()
        repository.열린_공연에_등록(eventId, token)
        repository.promote(eventId, 1)
        return token
    }

    @Test
    fun `승격되지 않은 토큰은 묶이지 않고 NOT_ACTIVE를 돌려준다`() {
        // given
        val token = UUID.randomUUID().toString()
        repository.열린_공연에_등록(eventId, token)

        // when
        val result = repository.bindPhone(eventId, token, phone(1))

        // then
        assertThat(result).isEqualTo(BindResult.NOT_ACTIVE)
    }

    @Test
    fun `활성 토큰은 처음 쓴 전화번호에 묶이고 다른 번호는 거절된다`() {
        // given
        val token = 승격된_토큰()

        // when
        val first = repository.bindPhone(eventId, token, phone(1))
        val other = repository.bindPhone(eventId, token, phone(2))

        // then
        assertThat(first).isEqualTo(BindResult.BOUND)
        assertThat(other).isEqualTo(BindResult.MISMATCH)
    }

    @Test
    fun `묶인 번호로는 계속 통과한다`() {
        // given
        val token = 승격된_토큰()
        repository.bindPhone(eventId, token, phone(1))

        // when
        val again = repository.bindPhone(eventId, token, phone(1))

        // then
        assertThat(again).isEqualTo(BindResult.BOUND)
    }

    @Test
    fun `묶어도 활성 수명은 늘지 않는다`() {
        // given
        val token = 승격된_토큰()
        redisTemplate.expire(activeKey(token), Duration.ofSeconds(30))

        // when
        repository.bindPhone(eventId, token, phone(1))

        // then
        assertThat(redisTemplate.getExpire(activeKey(token))).isBetween(1L, 30L)
    }

    @Tag("concurrency")
    @Test
    fun `동시에 서로 다른 번호로 묶으려 해도 하나만 묶인다`() {
        // given
        val token = 승격된_토큰()

        // when
        val results = 동시에_묶기(token, 20)

        // then
        assertThat(results.count { it == BindResult.BOUND }).isEqualTo(1)
        assertThat(results.count { it == BindResult.MISMATCH }).isEqualTo(19)
    }

    private fun 동시에_묶기(
        token: String,
        count: Int,
    ): List<BindResult> {
        val ready = CountDownLatch(count)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(count)
        return try {
            val futures =
                (1..count).map { seed ->
                    pool.submit<BindResult> {
                        ready.countDown()
                        start.await()
                        repository.bindPhone(eventId, token, phone(seed))
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
