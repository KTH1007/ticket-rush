package com.ticketrush.payment.application

import com.ticketrush.reservation.ReservationLookupPolicyProperties
import com.ticketrush.reservation.domain.ReservationLookupFailedException
import com.ticketrush.reservation.domain.ReservationLookupRateLimitedException
import com.ticketrush.reservation.domain.ReservationLookupRateLimiterPort
import com.ticketrush.reservation.infrastructure.ReservationLookupRateLimiterRedisAdapter
import com.ticketrush.shared.ClientIp
import com.ticketrush.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.util.UUID
import kotlin.test.Test

// 취소 경로에 IP 한도가 실제로 연결돼 있는지 본다. 한도를 작게 만든 제한기로 바꿔 끼운다
@Import(PaymentCancelIpLimitTest.SmallLimitConfig::class)
class PaymentCancelIpLimitTest : IntegrationTest() {
    @Autowired
    lateinit var cancelService: PaymentCancelCommandService

    @TestConfiguration
    class SmallLimitConfig {
        @Bean
        @Primary
        fun smallLimitRateLimiter(redisTemplate: StringRedisTemplate): ReservationLookupRateLimiterPort =
            ReservationLookupRateLimiterRedisAdapter(
                redisTemplate,
                ReservationLookupPolicyProperties(
                    maxAttempts = 5,
                    blockWindow = Duration.ofMinutes(10),
                    ipMaxAttempts = IP_LIMIT,
                    ipBlockWindow = Duration.ofMinutes(10),
                ),
            )
    }

    private val clientIp = ClientIp("ip-${UUID.randomUUID()}")

    private fun 없는_예매번호(): String = "NOEXIST${UUID.randomUUID().toString().take(5)}"

    @Test
    fun `예약번호를 바꿔 가며 같은 IP가 실패를 쌓으면 취소도 429로 차단된다`() {
        // given
        repeat(IP_LIMIT) {
            assertThatThrownBy { cancelService.cancel(없는_예매번호(), "01012345678", clientIp) }
                .isInstanceOf(ReservationLookupFailedException::class.java)
        }

        // when & then
        assertThatThrownBy { cancelService.cancel(없는_예매번호(), "01012345678", clientIp) }
            .isInstanceOfSatisfying(ReservationLookupRateLimitedException::class.java) {
                assertThat(it.retryAfter).isNotNull()
            }
    }

    @Test
    fun `다른 IP의 취소는 영향받지 않는다`() {
        // given
        repeat(IP_LIMIT + 1) { runCatching { cancelService.cancel(없는_예매번호(), "01012345678", clientIp) } }

        // when & then
        assertThatThrownBy { cancelService.cancel(없는_예매번호(), "01012345678", ClientIp("ip-${UUID.randomUUID()}")) }
            .isInstanceOf(ReservationLookupFailedException::class.java)
    }

    private companion object {
        const val IP_LIMIT = 3
    }
}
