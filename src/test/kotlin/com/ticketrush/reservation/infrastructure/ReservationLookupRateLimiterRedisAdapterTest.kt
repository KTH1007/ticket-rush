package com.ticketrush.reservation.infrastructure

import com.ticketrush.reservation.domain.ReservationLookupRateLimiterPort
import com.ticketrush.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.Test

class ReservationLookupRateLimiterRedisAdapterTest : IntegrationTest() {
    @Autowired
    lateinit var rateLimiter: ReservationLookupRateLimiterPort

    @Test
    fun `실패를 기록하면 카운트가 증가한다`() {
        // given
        val reservationNo = 예매번호()

        // when
        val first = rateLimiter.recordFailure(reservationNo)
        val second = rateLimiter.recordFailure(reservationNo)

        // then
        assertThat(first).isEqualTo(1)
        assertThat(second).isEqualTo(2)
    }

    @Test
    fun `임계치 미만이면 차단되지 않는다`() {
        // given
        val reservationNo = 예매번호()
        repeat(4) { rateLimiter.recordFailure(reservationNo) }

        // when & then
        assertThat(rateLimiter.isBlocked(reservationNo)).isFalse()
    }

    @Test
    fun `임계치에 도달하면 차단된다`() {
        // given
        val reservationNo = 예매번호()
        repeat(5) { rateLimiter.recordFailure(reservationNo) }

        // when & then
        assertThat(rateLimiter.isBlocked(reservationNo)).isTrue()
    }

    @Test
    fun `reset하면 카운트가 지워지고 차단이 풀린다`() {
        // given
        val reservationNo = 예매번호()
        repeat(5) { rateLimiter.recordFailure(reservationNo) }

        // when
        rateLimiter.reset(reservationNo)

        // then
        assertThat(rateLimiter.isBlocked(reservationNo)).isFalse()
    }

    @Test
    fun `다른 예매번호끼리는 카운트가 섞이지 않는다`() {
        // given
        val a = 예매번호()
        val b = 예매번호()
        repeat(5) { rateLimiter.recordFailure(a) }

        // when & then
        assertThat(rateLimiter.isBlocked(a)).isTrue()
        assertThat(rateLimiter.isBlocked(b)).isFalse()
    }

    private fun 예매번호(): String = "RESNO${System.nanoTime()}"
}
