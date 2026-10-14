package com.ticketrush.queue.infrastructure

import com.ticketrush.queue.QueuePolicyProperties
import com.ticketrush.support.IntegrationTest
import com.ticketrush.support.열린_공연에_등록
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.UUID
import kotlin.test.Test

class QueuePromoteScriptTest : IntegrationTest() {
    @Autowired
    lateinit var redisTemplate: StringRedisTemplate

    @Autowired
    lateinit var repository: QueueRedisAdapter

    @Autowired
    lateinit var policy: QueuePolicyProperties

    private val eventId = System.nanoTime()

    private fun 등록(): String = UUID.randomUUID().toString().also { repository.열린_공연에_등록(eventId, it) }

    // token 키가 먼저 만료된 토큰(유령)을 흉내 낸다. 대기 줄에는 남아 있다
    private fun 만료시키기(token: String) {
        redisTemplate.delete("queue:$eventId:token:$token")
    }

    private fun 대기_인원(): Long = redisTemplate.opsForZSet().zCard("queue:$eventId:waiting") ?: 0

    @Test
    fun `만료된 토큰은 건너뛰고 살아 있는 토큰을 승격한다`() {
        // given
        val expiredFirst = 등록()
        val expiredSecond = 등록()
        val live = 등록()
        만료시키기(expiredFirst)
        만료시키기(expiredSecond)

        // when
        val promoted = repository.promote(eventId, 1)

        // then
        assertThat(promoted).containsExactly(live)
    }

    @Test
    fun `건너뛴 만료 토큰의 순번도 마지막 승격 순번에 반영된다`() {
        // given
        val expiredFirst = 등록()
        val expiredSecond = 등록()
        val live = 등록()
        만료시키기(expiredFirst)
        만료시키기(expiredSecond)

        // when
        repository.promote(eventId, 1)

        // then
        assertThat(repository.findStatus(eventId, live)!!.lastPromotedSequence).isEqualTo(3)
        assertThat(대기_인원()).isEqualTo(0)
    }

    // 승격 한 번이 만료된 토큰을 무한정 꺼내지 않게 꺼내는 수에 상한(요청 수의 10배)이 있다
    @Test
    fun `만료된 토큰이 아무리 많아도 한 번에 꺼내는 수에 상한이 있다`() {
        // given
        val expired = (1..15).map { 등록() }
        val live = 등록()
        expired.forEach { 만료시키기(it) }

        // when & then
        assertThat(repository.promote(eventId, 1)).isEmpty()
        assertThat(대기_인원()).isEqualTo(6)
        assertThat(repository.promote(eventId, 1)).containsExactly(live)
    }

    @Test
    fun `대기 줄이 비면 활성 공연 집합에서 빠진다`() {
        // given
        등록()
        등록()

        // when
        repository.promote(eventId, 1)

        // then
        assertThat(repository.activeEventIds()).contains(eventId)

        // when
        repository.promote(eventId, 1)

        // then
        assertThat(repository.activeEventIds()).doesNotContain(eventId)
    }

    @Test
    fun `승격하면 활성 키에 active-ttl이 걸린다`() {
        // given
        val token = 등록()

        // when
        repository.promote(eventId, 1)

        // then
        val ttl = redisTemplate.getExpire("queue:$eventId:active:$token")!!
        assertThat(ttl).isBetween(policy.activeTtl.toSeconds() - 5, policy.activeTtl.toSeconds())
    }
}
