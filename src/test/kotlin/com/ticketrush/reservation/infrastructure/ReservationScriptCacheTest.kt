package com.ticketrush.reservation.infrastructure

import com.ticketrush.reservation.ReservationLookupPolicyProperties
import com.ticketrush.reservation.SeatPolicyProperties
import com.ticketrush.reservation.domain.ReservationLookupRateLimiterPort
import com.ticketrush.reservation.domain.SeatHoldFilterPort
import com.ticketrush.shared.ClientIp
import com.ticketrush.support.IntegrationTest
import com.ticketrush.support.스크립트_캐시_비우기
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test

// 스크립트 캐시가 비어도 선점과 조회 제한이 값까지 맞게 동작하는지 본다. 해제는 오류를 삼키는 쪽이라 예외가 아니라 결과 값으로 확인한다
class ReservationScriptCacheTest : IntegrationTest() {
    @Autowired
    lateinit var redisTemplate: StringRedisTemplate

    @Autowired
    lateinit var seatHoldFilter: SeatHoldFilterPort

    @Autowired
    lateinit var rateLimiter: ReservationLookupRateLimiterPort

    @Autowired
    lateinit var lookupPolicy: ReservationLookupPolicyProperties

    @Autowired
    lateinit var seatPolicy: SeatPolicyProperties

    private val eventId = System.nanoTime()

    @Test
    fun `스크립트 캐시가 비어 있어도 좌석 선점이 되고 같은 좌석은 막힌다`() {
        // given
        redisTemplate.스크립트_캐시_비우기()

        // when
        val first = seatHoldFilter.tryClaim(eventId, listOf(1L, 2L), "token-a")
        val conflict = seatHoldFilter.tryClaim(eventId, listOf(2L), "token-b")

        // then
        assertThat(first).isTrue()
        assertThat(conflict).isFalse()
    }

    @Test
    fun `스크립트 캐시가 비어 있어도 좌석 선점 해제가 되어 다른 사람이 잡을 수 있다`() {
        // given
        seatHoldFilter.tryClaim(eventId, listOf(1L, 2L), "token-a")
        redisTemplate.스크립트_캐시_비우기()

        // when
        seatHoldFilter.release(eventId, listOf(1L, 2L), "token-a")

        // then
        assertThat(seatHoldFilter.tryClaim(eventId, listOf(1L, 2L), "token-b")).isTrue()
    }

    @Test
    fun `스크립트 캐시가 비어 있어도 결제 실패 단축이 적용된다`() {
        // given
        seatHoldFilter.tryClaim(eventId, listOf(1L), "token-a")
        redisTemplate.스크립트_캐시_비우기()

        // when
        seatHoldFilter.shortenAfterPaymentFailure(eventId, listOf(1L), "token-a")

        // then
        assertThat(마크_남은_시간(1L)).isLessThanOrEqualTo(seatPolicy.paymentFailedHoldTtl.toMillis())
    }

    @Test
    fun `스크립트 캐시가 비어 있어도 판매 연장이 적용된다`() {
        // given
        seatHoldFilter.tryClaim(eventId, listOf(1L), "token-a")
        redisTemplate.스크립트_캐시_비우기()

        // when
        seatHoldFilter.extendForSold(eventId, listOf(1L), "token-a")

        // then
        assertThat(마크_남은_시간(1L)).isGreaterThan(seatPolicy.holdTtl.toMillis())
    }

    @Test
    fun `스크립트 캐시가 비어 있어도 조회 시도 제한이 센다`() {
        // given
        val reservationNo = UUID.randomUUID().toString()
        redisTemplate.스크립트_캐시_비우기()

        // when
        val allowed = (1..lookupPolicy.maxAttempts).map { rateLimiter.tryReserveAttempt(reservationNo) }
        val blocked = rateLimiter.tryReserveAttempt(reservationNo)

        // then
        assertThat(allowed).allMatch { it }
        assertThat(blocked).isFalse()
    }

    @Test
    fun `스크립트 캐시가 비어 있어도 IP별 조회 실패를 센다`() {
        // given
        val clientIp = ClientIp("ip-${UUID.randomUUID()}")
        redisTemplate.스크립트_캐시_비우기()

        // when
        val allowed = rateLimiter.tryReserveIpAttempt(clientIp)

        // then
        assertThat(allowed).isTrue()
        assertThat(redisTemplate.opsForValue().get("reservation:lookup-fail-ip:${clientIp.value}")).isEqualTo("1")
    }

    @Test
    fun `스크립트 캐시가 비어 있어도 조회 시도 슬롯 해제가 되어 다시 허용된다`() {
        // given
        val reservationNo = UUID.randomUUID().toString()
        repeat(lookupPolicy.maxAttempts) { rateLimiter.tryReserveAttempt(reservationNo) }
        assertThat(rateLimiter.tryReserveAttempt(reservationNo)).isFalse()
        redisTemplate.스크립트_캐시_비우기()

        // when
        rateLimiter.releaseAttempt(reservationNo)

        // then
        assertThat(rateLimiter.tryReserveAttempt(reservationNo)).isTrue()
    }

    private fun 마크_남은_시간(seatId: Long): Long = redisTemplate.getExpire("seat:hold:$eventId:$seatId", TimeUnit.MILLISECONDS)
}
