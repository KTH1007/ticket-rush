package com.ticketrush.reservation.infrastructure

import com.ticketrush.reservation.ReservationLookupPolicyProperties
import com.ticketrush.reservation.domain.ReservationLookupRateLimiterPort
import com.ticketrush.shared.ClientIp
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.core.io.ClassPathResource
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Repository
import java.time.Duration
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

@Repository
class ReservationLookupRateLimiterRedisAdapter(
    private val redisTemplate: StringRedisTemplate,
    private val policy: ReservationLookupPolicyProperties,
) : ReservationLookupRateLimiterPort {
    override fun tryReserveAttempt(reservationNo: String): Boolean =
        reserve(failureKey(reservationNo), policy.maxAttempts, policy.blockWindow)

    override fun tryReserveIpAttempt(clientIp: ClientIp): Boolean =
        reserve(ipFailureKey(clientIp), policy.ipMaxAttempts, policy.ipBlockWindow)

    override fun releaseAttempt(reservationNo: String) = release(failureKey(reservationNo))

    override fun releaseIpAttempt(clientIp: ClientIp) = release(ipFailureKey(clientIp))

    override fun retryAfter(reservationNo: String): Duration = remaining(failureKey(reservationNo))

    override fun retryAfterForIp(clientIp: ClientIp): Duration = remaining(ipFailureKey(clientIp))

    private fun reserve(
        key: String,
        maxAttempts: Int,
        window: Duration,
    ): Boolean {
        val result = redisTemplate.execute(reserveScript, listOf(key), maxAttempts.toString(), window.toSeconds().toString())
        return result == 1L
    }

    // 이미 성공한 조회나 취소 뒤의 부가 작업이라 실패해도 요청 결과를 바꾸지 않는다. 슬롯이 안 돌아가면 그 윈도우의 허용이 하나 줄 뿐이라 안전한 쪽이다
    private fun release(key: String) {
        try {
            redisTemplate.execute(releaseScript, listOf(key))
        } catch (e: DataAccessException) {
            logger.warn(e) { "조회 레이트리밋 슬롯 해제에 실패했습니다. 요청 결과에는 영향이 없습니다" }
        }
    }

    // 읽는 사이 윈도우가 끝났으면 바로 다시 시도해도 되므로 최소값을 준다
    private fun remaining(key: String): Duration {
        val millis = redisTemplate.getExpire(key, TimeUnit.MILLISECONDS)
        return if (millis > 0) Duration.ofMillis(millis) else MIN_RETRY_AFTER
    }

    private fun failureKey(reservationNo: String): String = "reservation:lookup-fail:$reservationNo"

    private fun ipFailureKey(clientIp: ClientIp): String = "reservation:lookup-fail-ip:${clientIp.value}"

    companion object {
        private val MIN_RETRY_AFTER: Duration = Duration.ofSeconds(1)

        private val reserveScript =
            DefaultRedisScript<Long>().apply {
                setLocation(ClassPathResource("scripts/reservation-lookup-rate-limit-reserve.lua"))
                resultType = Long::class.java
            }
        private val releaseScript =
            DefaultRedisScript<Long>().apply {
                setLocation(ClassPathResource("scripts/reservation-lookup-rate-limit-release.lua"))
                resultType = Long::class.java
            }
    }
}
