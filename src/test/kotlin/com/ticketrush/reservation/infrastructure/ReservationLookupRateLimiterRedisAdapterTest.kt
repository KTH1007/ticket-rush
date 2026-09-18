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
    fun `임계치 미만이면 예약에 성공한다`() {
        // given
        val reservationNo = 예매번호()

        // when
        val results = (1..4).map { rateLimiter.tryReserveAttempt(reservationNo) }

        // then
        assertThat(results).containsOnly(true)
    }

    @Test
    fun `임계치만큼 예약하면 그 다음부터는 실패한다`() {
        // given
        val reservationNo = 예매번호()
        repeat(5) { rateLimiter.tryReserveAttempt(reservationNo) }

        // when
        val blocked = rateLimiter.tryReserveAttempt(reservationNo)

        // then
        assertThat(blocked).isFalse()
    }

    @Test
    fun `release하면 그만큼 다시 예약할 수 있다`() {
        // given
        val reservationNo = 예매번호()
        repeat(5) { rateLimiter.tryReserveAttempt(reservationNo) }

        // when
        rateLimiter.releaseAttempt(reservationNo)

        // then
        assertThat(rateLimiter.tryReserveAttempt(reservationNo)).isTrue()
    }

    @Test
    fun `이미 차단된 뒤에는 release해도 딱 하나만 다시 열린다`() {
        // given
        val reservationNo = 예매번호()
        repeat(5) { rateLimiter.tryReserveAttempt(reservationNo) }
        rateLimiter.releaseAttempt(reservationNo)

        // when
        val first = rateLimiter.tryReserveAttempt(reservationNo)
        val second = rateLimiter.tryReserveAttempt(reservationNo)

        // then
        assertThat(first).isTrue()
        assertThat(second).isFalse()
    }

    @Test
    fun `다른 예매번호끼리는 카운트가 섞이지 않는다`() {
        // given
        val a = 예매번호()
        val b = 예매번호()
        repeat(5) { rateLimiter.tryReserveAttempt(a) }

        // when & then
        assertThat(rateLimiter.tryReserveAttempt(a)).isFalse()
        assertThat(rateLimiter.tryReserveAttempt(b)).isTrue()
    }

    private fun 예매번호(): String = "RESNO${System.nanoTime()}"
}
