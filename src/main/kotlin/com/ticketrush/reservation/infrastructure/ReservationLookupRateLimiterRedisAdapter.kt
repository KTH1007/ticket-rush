package com.ticketrush.reservation.infrastructure

import com.ticketrush.reservation.ReservationLookupPolicyProperties
import com.ticketrush.reservation.domain.ReservationLookupRateLimiterPort
import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Repository

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

    override fun releaseAttempt(reservationNo: String) {
        redisTemplate.execute(releaseScript, listOf(failureKey(reservationNo)))
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
