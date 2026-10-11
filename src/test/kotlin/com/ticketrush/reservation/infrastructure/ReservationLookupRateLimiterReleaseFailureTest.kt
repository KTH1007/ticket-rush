package com.ticketrush.reservation.infrastructure

import com.ticketrush.reservation.ReservationLookupPolicyProperties
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThatCode
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import java.time.Duration
import kotlin.test.Test

// 해제는 이미 성공한 조회나 취소 뒤의 부가 작업이라, 실패해도 요청 결과(특히 환불까지 끝난 취소)를 500으로 바꾸면 안 된다
class ReservationLookupRateLimiterReleaseFailureTest {
    private val redisTemplate = mockk<StringRedisTemplate>()
    private val policy = ReservationLookupPolicyProperties(maxAttempts = 5, blockWindow = Duration.ofMinutes(10))
    private val adapter = ReservationLookupRateLimiterRedisAdapter(redisTemplate, policy)

    @Test
    fun `release 중 Redis 오류가 나도 예외를 던지지 않는다`() {
        every { redisTemplate.execute(any<RedisScript<Long>>(), any<List<String>>()) } throws RedisConnectionFailureException("연결 실패")

        assertThatCode { adapter.releaseAttempt("ABCDEFGH2345") }.doesNotThrowAnyException()
    }
}
