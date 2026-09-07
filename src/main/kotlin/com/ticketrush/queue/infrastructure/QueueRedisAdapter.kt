package com.ticketrush.queue.infrastructure

import com.ticketrush.queue.domain.QueueRepositoryPort
import com.ticketrush.queue.domain.QueueStatus
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Repository
import java.time.Duration

@Repository
class QueueRedisAdapter(
    private val redisTemplate: StringRedisTemplate,
    @Value("\${ticket-rush.queue.active-ttl}") private val activeTtl: Duration,
    @Value("\${ticket-rush.queue.token-ttl}") private val tokenTtl: Duration,
    @Value("\${ticket-rush.queue.promotion-budget-per-second}") private val budgetPerSecond: Int,
) : QueueRepositoryPort {
    override fun register(
        eventId: Long,
        token: String,
    ): Long {
        val keys =
            listOf(
                "queue:$eventId:seq",
                "queue:$eventId:waiting",
                "queue:$eventId:token:$token",
                ACTIVE_EVENT_IDS_KEY,
            )
        return redisTemplate.execute(registerScript, keys, token, tokenTtl.toSeconds().toString(), eventId.toString())
            ?: error("대기열 등록 실패 : seq 반환값 없음")
    }

    override fun promote(
        eventId: Long,
        count: Int,
    ): List<String> {
        val keys =
            listOf(
                "queue:$eventId:waiting",
                "queue:$eventId:lastPromotedSeq",
                ACTIVE_EVENT_IDS_KEY,
            )
        val result =
            redisTemplate.execute(
                promoteScript,
                keys,
                count.toString(),
                activeTtl.toSeconds().toString(),
                eventId.toString(),
            )
        @Suppress("UNCHECKED_CAST")
        return (result as? List<String>).orEmpty()
    }

    override fun findStatus(
        eventId: Long,
        token: String,
    ): QueueStatus? {
        val keys =
            listOf(
                "queue:$eventId:token:$token",
                "queue:$eventId:active:$token",
                "queue:$eventId:lastPromotedSeq",
            )

        @Suppress("UNCHECKED_CAST")
        val result = redisTemplate.execute(statusScript, keys) as? List<Long> ?: return null
        if (result[0] != 1L) return null
        return QueueStatus(
            sequence = result[1],
            active = result[2] == 1L,
            lastPromotedSequence = result[3],
        )
    }

    override fun activeEventIds(): Set<Long> =
        redisTemplate
            .opsForSet()
            .members(ACTIVE_EVENT_IDS_KEY)
            .orEmpty()
            .map { it.toLong() }
            .toSet()

    override fun reserveGlobalBudget(count: Int): Int {
        val currentSecond = System.currentTimeMillis() / 1000
        val keys = listOf("queue:promotionBudget:$currentSecond")
        val granted = redisTemplate.execute(budgetReserveScript, keys, count.toString(), budgetPerSecond.toString(), "5")
        return granted?.toInt() ?: 0
    }

    companion object {
        private val statusScript: RedisScript<List<*>> =
            DefaultRedisScript<List<*>>().apply {
                setLocation(ClassPathResource("scripts/queue-status.lua"))
                resultType = List::class.java
            }

        private val budgetReserveScript: RedisScript<Long> =
            DefaultRedisScript<Long>().apply {
                setLocation(ClassPathResource("scripts/queue-budget-reserve.lua"))
                resultType = Long::class.java
            }

        private const val ACTIVE_EVENT_IDS_KEY = "queue:activeEventIds"

        private val registerScript: RedisScript<Long> =
            DefaultRedisScript<Long>().apply {
                setLocation(ClassPathResource("scripts/queue-register.lua"))
                resultType = Long::class.java
            }

        private val promoteScript: RedisScript<List<*>> =
            DefaultRedisScript<List<*>>().apply {
                setLocation(ClassPathResource("scripts/queue-promote.lua"))
                resultType = List::class.java
            }
    }
}
