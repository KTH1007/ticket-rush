package com.ticketrush.reservation.application

import com.ticketrush.reservation.ReservationLookupPolicyProperties
import com.ticketrush.reservation.domain.GradeQueryPort
import com.ticketrush.reservation.domain.ReservationLookupFailedException
import com.ticketrush.reservation.domain.ReservationLookupRateLimitedException
import com.ticketrush.reservation.domain.ReservationRepositoryPort
import com.ticketrush.reservation.domain.SeatQueryPort
import com.ticketrush.reservation.infrastructure.ReservationLookupRateLimiterRedisAdapter
import com.ticketrush.shared.ClientIp
import com.ticketrush.shared.PhoneHasher
import com.ticketrush.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.util.UUID
import kotlin.test.Test

// 조회 경로에 IP 한도가 실제로 연결돼 있는지 본다. 한도를 작게 만든 제한기로 서비스를 직접 만든다
class ReservationLookupQueryServiceIpLimitTest : IntegrationTest() {
    @Autowired
    lateinit var redisTemplate: StringRedisTemplate

    @Autowired
    lateinit var reservationRepository: ReservationRepositoryPort

    @Autowired
    lateinit var seatRepository: SeatQueryPort

    @Autowired
    lateinit var gradeRepository: GradeQueryPort

    @Autowired
    lateinit var phoneHasher: PhoneHasher

    private val ipLimit = 3
    private val clientIp = ClientIp("ip-${UUID.randomUUID()}")

    private fun service() =
        ReservationLookupQueryService(
            reservationRepository,
            seatRepository,
            gradeRepository,
            ReservationLookupRateLimiterRedisAdapter(
                redisTemplate,
                ReservationLookupPolicyProperties(
                    maxAttempts = 5,
                    blockWindow = Duration.ofMinutes(10),
                    ipMaxAttempts = ipLimit,
                    ipBlockWindow = Duration.ofMinutes(10),
                ),
            ),
            phoneHasher,
        )

    private fun 없는_예매번호(): String = "NOEXIST${UUID.randomUUID().toString().take(5)}"

    @Test
    fun `예약번호를 바꿔 가며 같은 IP가 실패를 쌓으면 조회가 429로 차단된다`() {
        // given
        val service = service()
        repeat(ipLimit) {
            assertThatThrownBy { service.lookup(없는_예매번호(), "01012345678", clientIp) }
                .isInstanceOf(ReservationLookupFailedException::class.java)
        }

        // when & then
        assertThatThrownBy { service.lookup(없는_예매번호(), "01012345678", clientIp) }
            .isInstanceOfSatisfying(ReservationLookupRateLimitedException::class.java) {
                assertThat(it.retryAfter).isNotNull()
            }
    }

    @Test
    fun `다른 IP의 조회는 영향받지 않는다`() {
        // given
        val service = service()
        repeat(ipLimit + 1) { runCatching { service.lookup(없는_예매번호(), "01012345678", clientIp) } }

        // when & then
        assertThatThrownBy { service.lookup(없는_예매번호(), "01012345678", ClientIp("ip-${UUID.randomUUID()}")) }
            .isInstanceOf(ReservationLookupFailedException::class.java)
    }
}
