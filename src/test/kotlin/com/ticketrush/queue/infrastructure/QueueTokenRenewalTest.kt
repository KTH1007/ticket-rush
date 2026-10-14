package com.ticketrush.queue.infrastructure

import com.ticketrush.queue.QueuePolicyProperties
import com.ticketrush.support.IntegrationTest
import com.ticketrush.support.열린_공연에_등록
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.util.UUID
import kotlin.test.Test

class QueueTokenRenewalTest : IntegrationTest() {
    @Autowired
    lateinit var redisTemplate: StringRedisTemplate

    @Autowired
    lateinit var repository: QueueRedisAdapter

    @Autowired
    lateinit var policy: QueuePolicyProperties

    private val eventId = System.nanoTime()

    private fun tokenKey(token: String) = "queue:$eventId:token:$token"

    @Test
    fun `대기 중인 토큰은 순번을 조회하면 수명이 token-ttl로 다시 늘어난다`() {
        // given
        val token = UUID.randomUUID().toString()
        repository.열린_공연에_등록(eventId, token)
        redisTemplate.expire(tokenKey(token), Duration.ofSeconds(5))

        // when
        repository.findStatus(eventId, token)

        // then
        assertThat(redisTemplate.getExpire(tokenKey(token))).isGreaterThan(policy.tokenTtl.toSeconds() - 5)
    }

    @Test
    fun `이미 활성이 된 토큰은 조회해도 token 키 수명이 늘지 않는다`() {
        // given
        val token = UUID.randomUUID().toString()
        repository.열린_공연에_등록(eventId, token)
        repository.promote(eventId, 1)
        redisTemplate.expire(tokenKey(token), Duration.ofSeconds(5))

        // when
        val status = repository.findStatus(eventId, token)

        // then
        assertThat(status!!.active).isTrue()
        assertThat(redisTemplate.getExpire(tokenKey(token))).isLessThanOrEqualTo(5)
    }

    @Test
    fun `승격된 뒤 활성이 만료된 토큰은 조회해도 수명이 늘지 않는다`() {
        // given
        val token = UUID.randomUUID().toString()
        repository.열린_공연에_등록(eventId, token)
        repository.promote(eventId, 1)
        redisTemplate.delete("queue:$eventId:active:$token")
        redisTemplate.expire(tokenKey(token), Duration.ofSeconds(5))

        // when
        repository.findStatus(eventId, token)

        // then
        assertThat(redisTemplate.getExpire(tokenKey(token))).isLessThanOrEqualTo(5)
    }

    @Test
    fun `승격된 뒤 token 키가 먼저 만료돼도 활성 구간 안에서는 활성으로 조회된다`() {
        // given
        val token = UUID.randomUUID().toString()
        repository.열린_공연에_등록(eventId, token)
        repository.promote(eventId, 1)
        redisTemplate.delete(tokenKey(token))

        // when
        val status = repository.findStatus(eventId, token)

        // then
        assertThat(status!!.active).isTrue()
        assertThat(redisTemplate.hasKey(tokenKey(token))).isFalse()
    }

    @Test
    fun `만료된 토큰은 조회해도 되살아나지 않는다`() {
        // given
        val token = UUID.randomUUID().toString()
        repository.열린_공연에_등록(eventId, token)
        redisTemplate.delete(tokenKey(token))

        // when
        val status = repository.findStatus(eventId, token)

        // then
        assertThat(status).isNull()
        assertThat(redisTemplate.hasKey(tokenKey(token))).isFalse()
    }
}
