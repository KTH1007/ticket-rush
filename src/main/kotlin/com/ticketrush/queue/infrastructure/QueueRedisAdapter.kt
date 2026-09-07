package com.ticketrush.queue.infrastructure

import com.ticketrush.queue.domain.QueueRepositoryPort
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

    override fun findSequence(
        eventId: Long,
        token: String,
    ): Long? = redisTemplate.opsForValue().get("queue:$eventId:token:$token")?.toLong()

    override fun isActive(
        eventId: Long,
        token: String,
    ): Boolean = redisTemplate.hasKey("queue:$eventId:active:$token") == true

    override fun lastPromotedSequence(eventId: Long): Long =
        redisTemplate.opsForValue().get("queue:$eventId:lastPromotedSeq")?.toLong() ?: 0L

    override fun activeEventIds(): Set<Long> =
        redisTemplate
            .opsForSet()
            .members(ACTIVE_EVENT_IDS_KEY)
            .orEmpty()
            .map { it.toLong() }
            .toSet()

    companion object {
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
