package com.ticketrush.reservation.infrastructure

import com.ticketrush.reservation.ReservationLookupPolicyProperties
import com.ticketrush.reservation.domain.ReservationLookupRateLimiterPort
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.core.io.ClassPathResource
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Repository

private val logger = KotlinLogging.logger {}

@Repository
class ReservationLookupRateLimiterRedisAdapter(
    private val redisTemplate: StringRedisTemplate,
    private val policy: ReservationLookupPolicyProperties,
) : ReservationLookupRateLimiterPort {
    override fun tryReserveAttempt(reservationNo: String): Boolean {
        val result =
            redisTemplate.execute(
                reserveScript,
                listOf(failureKey(reservationNo)),
                policy.maxAttempts.toString(),
                policy.blockWindow.toSeconds().toString(),
            )
        return result == 1L
    }

    // 이미 성공한 조회나 취소 뒤의 부가 작업이라 실패해도 요청 결과를 바꾸지 않는다. 슬롯이 안 돌아가면 그 윈도우의 허용이 하나 줄 뿐이라 안전한 쪽이다
    override fun releaseAttempt(reservationNo: String) {
        try {
            redisTemplate.execute(releaseScript, listOf(failureKey(reservationNo)))
        } catch (e: DataAccessException) {
            logger.warn(e) { "조회 레이트리밋 슬롯 해제에 실패했습니다. 요청 결과에는 영향이 없습니다" }
        }
    }

    private fun failureKey(reservationNo: String): String = "reservation:lookup-fail:$reservationNo"

    companion object {
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
