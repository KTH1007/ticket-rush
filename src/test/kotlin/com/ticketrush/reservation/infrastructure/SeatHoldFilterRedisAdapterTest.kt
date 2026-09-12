package com.ticketrush.reservation.infrastructure

import com.ticketrush.reservation.domain.SeatHoldFilterPort
import com.ticketrush.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.UUID
import kotlin.test.Test

class SeatHoldFilterRedisAdapterTest : IntegrationTest() {
    @Autowired
    lateinit var seatHoldFilter: SeatHoldFilterPort

    @Autowired
    lateinit var redisTemplate: StringRedisTemplate

    @Test
    fun `아무도 안 잡은 좌석은 클레임에 성공한다`() {
        // given
        val eventId = System.nanoTime()
        val token = UUID.randomUUID().toString()

        // when
        val result = seatHoldFilter.tryClaim(eventId, listOf(1L), token)

        // then
        assertThat(result).isTrue()
        assertThat(redisTemplate.opsForValue().get("seat:hold:$eventId:1")).isEqualTo(token)
    }

    @Test
    fun `이미 잡힌 좌석이 섞여 있으면 전부 실패하고 앞서 잡은 것도 풀린다`() {
        // given
        val eventId = System.nanoTime()
        val otherToken = UUID.randomUUID().toString()
        redisTemplate.opsForValue().set("seat:hold:$eventId:2", otherToken)
        val token = UUID.randomUUID().toString()

        // when
        val result = seatHoldFilter.tryClaim(eventId, listOf(1L, 2L), token)

        // then
        assertThat(result).isFalse()
        assertThat(redisTemplate.opsForValue().get("seat:hold:$eventId:1")).isNull()
        assertThat(redisTemplate.opsForValue().get("seat:hold:$eventId:2")).isEqualTo(otherToken)
    }

    @Test
    fun `release하면 내가 잡은 키가 지워진다`() {
        // given
        val eventId = System.nanoTime()
        val token = UUID.randomUUID().toString()
        seatHoldFilter.tryClaim(eventId, listOf(1L, 2L), token)

        // when
        seatHoldFilter.release(eventId, listOf(1L, 2L), token)

        // then
        assertThat(redisTemplate.opsForValue().get("seat:hold:$eventId:1")).isNull()
        assertThat(redisTemplate.opsForValue().get("seat:hold:$eventId:2")).isNull()
    }

    @Test
    fun `다른 토큰이 잡은 키는 release해도 안 지워진다`() {
        // given
        val eventId = System.nanoTime()
        val otherToken = UUID.randomUUID().toString()
        redisTemplate.opsForValue().set("seat:hold:$eventId:1", otherToken)

        // when
        seatHoldFilter.release(eventId, listOf(1L), UUID.randomUUID().toString())

        // then
        assertThat(redisTemplate.opsForValue().get("seat:hold:$eventId:1")).isEqualTo(otherToken)
    }
}
