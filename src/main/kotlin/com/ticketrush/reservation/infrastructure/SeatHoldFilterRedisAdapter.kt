package com.ticketrush.reservation.infrastructure

import com.ticketrush.reservation.domain.SeatHoldFilterPort
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Repository
import java.time.Duration

@Repository
class SeatHoldFilterRedisAdapter(
    private val redisTemplate: StringRedisTemplate,
    @Value("\${ticket-rush.seat.hold-ttl}") private val holdTtl: Duration,
) : SeatHoldFilterPort {
    override fun tryClaim(
        eventId: Long,
        seatIds: List<Long>,
        holdToken: String,
    ): Boolean {
        val keys = seatIds.map { seatKey(eventId, it) }
        val result = redisTemplate.execute(claimScript, keys, holdToken, holdTtl.toMillis().toString())
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
