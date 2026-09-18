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
    override fun recordFailure(reservationNo: String): Long =
        redisTemplate.execute(incrScript, listOf(failureKey(reservationNo)), policy.blockWindow.toSeconds().toString()) ?: 0L

    override fun isBlocked(reservationNo: String): Boolean {
        val count = redisTemplate.opsForValue().get(failureKey(reservationNo))?.toLongOrNull() ?: 0L
        return count >= policy.maxAttempts
    }

    override fun reset(reservationNo: String) {
        redisTemplate.delete(failureKey(reservationNo))
    }

    private fun failureKey(reservationNo: String): String = "reservation:lookup-fail:$reservationNo"

    companion object {
        private val incrScript =
            DefaultRedisScript<Long>().apply {
                setLocation(ClassPathResource("scripts/reservation-lookup-rate-limit-incr.lua"))
                resultType = Long::class.java
            }
    }
}
