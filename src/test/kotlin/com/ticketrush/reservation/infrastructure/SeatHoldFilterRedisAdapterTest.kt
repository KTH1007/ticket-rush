package com.ticketrush.reservation.infrastructure

import com.ticketrush.reservation.SeatPolicyProperties
import com.ticketrush.reservation.domain.SeatHoldFilterPort
import com.ticketrush.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test

class SeatHoldFilterRedisAdapterTest : IntegrationTest() {
    @Autowired
    lateinit var seatHoldFilter: SeatHoldFilterPort

    @Autowired
    lateinit var redisTemplate: StringRedisTemplate

    @Autowired
    lateinit var seatPolicy: SeatPolicyProperties

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

    @Test
    fun `결제 실패 뒤 단축하면 내 마크의 남은 시간이 결제 실패 홀드 시간 이하로 줄어든다`() {
        // given
        val eventId = System.nanoTime()
        val token = UUID.randomUUID().toString()
        seatHoldFilter.tryClaim(eventId, listOf(1L, 2L), token)

        // when
        seatHoldFilter.shortenAfterPaymentFailure(eventId, listOf(1L, 2L), token)

        // then
        assertThat(남은_시간(eventId, 1L)).isBetween(1L, seatPolicy.paymentFailedHoldTtl.toMillis())
        assertThat(남은_시간(eventId, 2L)).isBetween(1L, seatPolicy.paymentFailedHoldTtl.toMillis())
    }

    @Test
    fun `이미 더 짧게 남은 마크는 단축해도 늘어나지 않는다`() {
        // given
        val eventId = System.nanoTime()
        val token = UUID.randomUUID().toString()
        redisTemplate.opsForValue().set("seat:hold:$eventId:1", token, Duration.ofSeconds(SHORT_SECONDS))

        // when
        seatHoldFilter.shortenAfterPaymentFailure(eventId, listOf(1L), token)

        // then
        assertThat(남은_시간(eventId, 1L)).isBetween(1L, Duration.ofSeconds(SHORT_SECONDS).toMillis())
    }

    @Test
    fun `단축은 남의 마크를 건드리지 않고 없는 마크를 되살리지 않는다`() {
        // given
        val eventId = System.nanoTime()
        seatHoldFilter.tryClaim(eventId, listOf(1L), UUID.randomUUID().toString())

        // when
        seatHoldFilter.shortenAfterPaymentFailure(eventId, listOf(1L, 2L), UUID.randomUUID().toString())

        // then
        assertThat(남은_시간(eventId, 1L)).isGreaterThan(seatPolicy.paymentFailedHoldTtl.toMillis())
        assertThat(redisTemplate.hasKey("seat:hold:$eventId:2")).isFalse()
    }

    @Test
    fun `판매 연장은 내 마크의 TTL을 홀드 시간보다 길고 판매 마크 시간 이하로 늘린다`() {
        // given
        val eventId = System.nanoTime()
        val token = UUID.randomUUID().toString()
        seatHoldFilter.tryClaim(eventId, listOf(1L, 2L), token)

        // when
        seatHoldFilter.extendForSold(eventId, listOf(1L, 2L), token)

        // then
        assertThat(남은_시간(eventId, 1L)).isGreaterThan(seatPolicy.holdTtl.toMillis()).isLessThanOrEqualTo(seatPolicy.soldMarkTtl.toMillis())
        assertThat(남은_시간(eventId, 2L)).isGreaterThan(seatPolicy.holdTtl.toMillis()).isLessThanOrEqualTo(seatPolicy.soldMarkTtl.toMillis())
    }

    @Test
    fun `판매 연장은 남의 마크를 건드리지 않고 없는 마크를 되살리지 않는다`() {
        // given
        val eventId = System.nanoTime()
        seatHoldFilter.tryClaim(eventId, listOf(1L), UUID.randomUUID().toString())

        // when
        seatHoldFilter.extendForSold(eventId, listOf(1L, 2L), UUID.randomUUID().toString())

        // then
        assertThat(남은_시간(eventId, 1L)).isLessThanOrEqualTo(seatPolicy.holdTtl.toMillis())
        assertThat(redisTemplate.hasKey("seat:hold:$eventId:2")).isFalse()
    }

    private fun 남은_시간(
        eventId: Long,
        seatId: Long,
    ): Long = redisTemplate.getExpire("seat:hold:$eventId:$seatId", TimeUnit.MILLISECONDS)

    private companion object {
        const val SHORT_SECONDS = 10L
    }
}
