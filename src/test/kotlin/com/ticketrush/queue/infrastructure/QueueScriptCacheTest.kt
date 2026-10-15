package com.ticketrush.queue.infrastructure

import com.ticketrush.support.IntegrationTest
import com.ticketrush.support.testClientIp
import com.ticketrush.support.스크립트_캐시_비우기
import com.ticketrush.support.스크립트가_캐시돼_있다
import com.ticketrush.support.열린_공연에_등록
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.UUID
import kotlin.test.Test

// Redis가 스크립트 캐시를 잃으면(재시작, 장애 조치) 해시 호출이 NOSCRIPT로 실패한다. 라이브러리가 전문으로 다시 보내는 폴백이 우리 호출에서 실제로 도는지 값으로 확인한다
class QueueScriptCacheTest : IntegrationTest() {
    @Autowired
    lateinit var redisTemplate: StringRedisTemplate

    @Autowired
    lateinit var repository: QueueRedisAdapter

    private val eventId = System.nanoTime()

    @Test
    fun `스크립트 캐시가 비어 있어도 등록이 된다`() {
        // given
        redisTemplate.스크립트_캐시_비우기()

        // when
        val sequence = repository.열린_공연에_등록(eventId, UUID.randomUUID().toString())

        // then
        assertThat(sequence).isEqualTo(1)
    }

    @Test
    fun `스크립트 캐시가 비어 있어도 순번 조회가 된다`() {
        // given
        val token = UUID.randomUUID().toString()
        repository.열린_공연에_등록(eventId, token)
        redisTemplate.스크립트_캐시_비우기()

        // when
        val status = repository.findStatus(eventId, token)

        // then
        assertThat(status!!.sequence).isEqualTo(1)
        assertThat(status.active).isFalse()
    }

    @Test
    fun `스크립트 캐시가 비어 있어도 승격이 된다`() {
        // given
        val token = UUID.randomUUID().toString()
        repository.열린_공연에_등록(eventId, token)
        redisTemplate.스크립트_캐시_비우기()

        // when
        val promoted = repository.promote(eventId, 1)

        // then
        assertThat(promoted).containsExactly(token)
    }

    @Test
    fun `스크립트 캐시가 비어 있어도 IP별 발급 수를 센다`() {
        // given
        redisTemplate.스크립트_캐시_비우기()

        // when
        repository.열린_공연에_등록(eventId, UUID.randomUUID().toString())

        // then
        assertThat(redisTemplate.opsForValue().get("queue:$eventId:register-ip:${testClientIp.value}")).isEqualTo("1")
    }

    @Test
    fun `캐시를 비운 뒤 한 번 실행하면 스크립트가 다시 캐시된다`() {
        // given
        redisTemplate.스크립트_캐시_비우기()
        assertThat(redisTemplate.스크립트가_캐시돼_있다("scripts/queue-register.lua")).isFalse()

        // when
        repository.열린_공연에_등록(eventId, UUID.randomUUID().toString())

        // then
        assertThat(redisTemplate.스크립트가_캐시돼_있다("scripts/queue-register.lua")).isTrue()
    }
}
