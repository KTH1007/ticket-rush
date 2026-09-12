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
        val keys = seatIds.map { seatKey(eventId, it) }
        val result = redisTemplate.execute(claimScript, keys, holdToken, seatPolicy.holdTtl.toMillis().toString())
        return result == 1L
    }

    override fun release(
        eventId: Long,
        seatIds: List<Long>,
        holdToken: String,
    ) {
        val keys = seatIds.map { seatKey(eventId, it) }
        redisTemplate.execute(releaseScript, keys, holdToken)
    }

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
    }
}
