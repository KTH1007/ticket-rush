package com.ticketrush.reservation.infrastructure

import com.ticketrush.reservation.ReservationLookupPolicyProperties
import com.ticketrush.reservation.domain.ReservationLookupRateLimitedException
import com.ticketrush.shared.ClientIp
import com.ticketrush.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.util.UUID
import kotlin.test.Test

// 테스트 설정은 IP 한도를 크게 올려 두므로, 작은 한도의 어댑터를 따로 만들고 테스트마다 다른 IP를 쓴다
class ReservationLookupIpLimitTest : IntegrationTest() {
    @Autowired
    lateinit var redisTemplate: StringRedisTemplate

    private val ipLimit = 3
    private val ipWindow = Duration.ofMinutes(10)
    private val numberLimit = 5
    private val clientIp = ClientIp("ip-${UUID.randomUUID()}")

    private fun limiter(
        numberLimit: Int = this.numberLimit,
        ipLimit: Int = this.ipLimit,
    ) = ReservationLookupRateLimiterRedisAdapter(
        redisTemplate,
        ReservationLookupPolicyProperties(
            maxAttempts = numberLimit,
            blockWindow = Duration.ofMinutes(10),
            ipMaxAttempts = ipLimit,
            ipBlockWindow = ipWindow,
        ),
    )

    private fun 예매번호(): String = "RESNO${UUID.randomUUID()}"

    private fun ipKey(ip: ClientIp = clientIp) = "reservation:lookup-fail-ip:${ip.value}"

    @Test
    fun `예약번호를 바꿔 가며 같은 IP가 한도를 넘으면 차단된다`() {
        // given
        val limiter = limiter()
        repeat(ipLimit) { limiter.reserveOrThrow(예매번호(), clientIp) }

        // when & then
        assertThatThrownBy { limiter.reserveOrThrow(예매번호(), clientIp) }
            .isInstanceOfSatisfying(ReservationLookupRateLimitedException::class.java) {
                assertThat(it.retryAfter).isPositive().isLessThanOrEqualTo(ipWindow)
            }
    }

    @Test
    fun `다른 IP는 영향받지 않는다`() {
        // given
        val limiter = limiter()
        repeat(ipLimit) { limiter.reserveOrThrow(예매번호(), clientIp) }

        // when & then
        limiter.reserveOrThrow(예매번호(), ClientIp("ip-${UUID.randomUUID()}"))
    }

    @Test
    fun `성공해서 돌려준 슬롯은 한도를 깎지 않는다`() {
        // given
        val limiter = limiter()

        // when
        repeat(ipLimit * 2) {
            val reservationNo = 예매번호()
            limiter.reserveOrThrow(reservationNo, clientIp)
            limiter.releaseAttempt(reservationNo, clientIp)
        }

        // then
        limiter.reserveOrThrow(예매번호(), clientIp)
    }

    @Test
    fun `IP 한도에 막힌 시도는 예약번호 카운터를 올리지 않는다`() {
        // given
        val limiter = limiter()
        repeat(ipLimit) { limiter.reserveOrThrow(예매번호(), clientIp) }
        val blockedNo = 예매번호()

        // when
        assertThatThrownBy { limiter.reserveOrThrow(blockedNo, clientIp) }.isInstanceOf(ReservationLookupRateLimitedException::class.java)

        // then
        assertThat(redisTemplate.hasKey("reservation:lookup-fail:$blockedNo")).isFalse()
    }

    @Test
    fun `예약번호 한도에 막힌 시도는 IP 슬롯을 돌려주지 않는다`() {
        // given
        val limiter = limiter(numberLimit = 2, ipLimit = 10)
        val reservationNo = 예매번호()
        repeat(2) { limiter.reserveOrThrow(reservationNo, clientIp) }

        // when
        assertThatThrownBy {
            limiter.reserveOrThrow(
                reservationNo,
                clientIp,
            )
        }.isInstanceOf(ReservationLookupRateLimitedException::class.java)

        // then
        assertThat(redisTemplate.opsForValue().get(ipKey())).isEqualTo("3")
    }

    @Test
    fun `IP 실패 키에는 윈도우 TTL이 걸린다`() {
        // given
        val limiter = limiter()

        // when
        limiter.reserveOrThrow(예매번호(), clientIp)

        // then
        assertThat(redisTemplate.getExpire(ipKey())).isBetween(1L, ipWindow.seconds)
    }

    @Test
    fun `차단 때 남은 시간은 윈도우 안이고 키가 없으면 최소 1초다`() {
        // given
        val limiter = limiter()
        repeat(ipLimit) { limiter.reserveOrThrow(예매번호(), clientIp) }

        // when & then
        assertThat(limiter.retryAfterForIp(clientIp)).isPositive().isLessThanOrEqualTo(ipWindow)
        assertThat(limiter.retryAfterForIp(ClientIp("ip-${UUID.randomUUID()}"))).isEqualTo(Duration.ofSeconds(1))
    }
}
