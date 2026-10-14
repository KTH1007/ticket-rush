package com.ticketrush.reservation.infrastructure

import com.ticketrush.reservation.SeatPolicyProperties
import com.ticketrush.reservation.domain.SeatHoldFilterPort
import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Repository

@Repository
class SeatHoldFilterRedisAdapter(
    private val redisTemplate: StringRedisTemplate,
    private val seatPolicy: SeatPolicyProperties,
) : SeatHoldFilterPort {
    override fun tryClaim(
        eventId: Long,
        seatIds: List<Long>,
        holdToken: String,
    ): Boolean {
        val result = redisTemplate.execute(claimScript, seatKeys(eventId, seatIds), holdToken, seatPolicy.holdTtl.toMillis().toString())
        return result == 1L
    }

    override fun release(
        eventId: Long,
        seatIds: List<Long>,
        holdToken: String,
    ) {
        redisTemplate.execute(releaseScript, seatKeys(eventId, seatIds), holdToken)
    }

    override fun shortenAfterPaymentFailure(
        eventId: Long,
        seatIds: List<Long>,
        holdToken: String,
    ) {
        val ttlMillis = seatPolicy.paymentFailedHoldTtl.toMillis().toString()
        redisTemplate.execute(shortenScript, seatKeys(eventId, seatIds), holdToken, ttlMillis)
    }

    override fun extendForSold(
        eventId: Long,
        seatIds: List<Long>,
        holdToken: String,
    ) {
        val ttlMillis = seatPolicy.soldMarkTtl.toMillis().toString()
        redisTemplate.execute(extendScript, seatKeys(eventId, seatIds), holdToken, ttlMillis)
    }

    private fun seatKeys(
        eventId: Long,
        seatIds: List<Long>,
    ): List<String> = seatIds.map { seatKey(eventId, it) }

    private fun seatKey(
        eventId: Long,
        seatId: Long,
    ): String = "seat:hold:$eventId:$seatId"

    companion object {
        private val claimScript =
            DefaultRedisScript<Long>().apply {
                setLocation(ClassPathResource("scripts/seat-claim.lua"))
                resultType = Long::class.java
            }
        private val releaseScript =
            DefaultRedisScript<Long>().apply {
                setLocation(ClassPathResource("scripts/seat-release.lua"))
                resultType = Long::class.java
            }
        private val shortenScript =
            DefaultRedisScript<Long>().apply {
                setLocation(ClassPathResource("scripts/seat-shorten.lua"))
                resultType = Long::class.java
            }
        private val extendScript =
            DefaultRedisScript<Long>().apply {
                setLocation(ClassPathResource("scripts/seat-extend.lua"))
                resultType = Long::class.java
            }
    }
}
