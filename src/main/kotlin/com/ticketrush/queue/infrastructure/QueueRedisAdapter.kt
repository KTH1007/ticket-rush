package com.ticketrush.queue.infrastructure

import com.ticketrush.queue.QueuePolicyProperties
import com.ticketrush.queue.domain.BindResult
import com.ticketrush.queue.domain.EventSchedule
import com.ticketrush.queue.domain.QueueRepositoryPort
import com.ticketrush.queue.domain.QueueStatus
import com.ticketrush.queue.domain.RegisterResult
import com.ticketrush.shared.ClientIp
import com.ticketrush.shared.PhoneHash
import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Repository
import java.time.Duration
import java.time.Instant
import java.util.HexFormat

// 포트 메서드마다 Lua 호출과 키 목록 만들기가 짝이라 함수가 많다
@Suppress("TooManyFunctions")
@Repository
class QueueRedisAdapter(
    private val redisTemplate: StringRedisTemplate,
    private val policy: QueuePolicyProperties,
    // 테스트가 다른 테스트의 승격과 섞이지 않게 예산 키를 격리하려고 바꿀 수 있다
    private val budgetKeyPrefix: String = DEFAULT_BUDGET_KEY_PREFIX,
) : QueueRepositoryPort {
    override fun register(
        eventId: Long,
        token: String,
        clientIp: ClientIp,
        scheduleHint: EventSchedule?,
    ): RegisterResult {
        @Suppress("UNCHECKED_CAST")
        val result =
            redisTemplate.execute(
                registerScript,
                registerKeys(eventId, token, clientIp),
                token,
                policy.tokenTtl.toSeconds().toString(),
                eventId.toString(),
                policy.scheduleCacheTtl.toSeconds().toString(),
                scheduleHint?.opensAt.toArg(),
                scheduleHint?.startsAt.toArg(),
                policy.registerLimit.maxPerIp.toString(),
                policy.registerLimit.window
                    .toSeconds()
                    .toString(),
                policy.maxWaiting.toString(),
            ) as? List<Long> ?: error("대기열 등록 실패 : 반환값 없음")
        return toRegisterResult(result)
    }

    // 일정을 모르는 호출은 빈 문자열을 넘겨 스크립트가 힌트 없음으로 보게 한다
    private fun Instant?.toArg(): String = this?.toEpochMilli()?.toString().orEmpty()

    private fun registerKeys(
        eventId: Long,
        token: String,
        clientIp: ClientIp,
    ) = listOf(
        "queue:$eventId:seq",
        "queue:$eventId:waiting",
        "queue:$eventId:token:$token",
        ACTIVE_EVENT_IDS_KEY,
        "queue:$eventId:opensAt",
        "queue:$eventId:startsAt",
        "queue:$eventId:register-ip:${clientIp.value}",
        "queue:$eventId:lastPromotedSeq",
        cleanedKey(eventId),
    )

    private fun toRegisterResult(result: List<Long>): RegisterResult =
        when (result[0]) {
            REGISTERED -> RegisterResult.Registered(result[1])
            NOT_OPEN -> RegisterResult.NotOpen(Instant.ofEpochMilli(result[1]))
            CLOSED -> RegisterResult.Closed(Instant.ofEpochMilli(result[1]))
            RATE_LIMITED -> RegisterResult.RateLimited(retryAfterOf(result[1]))
            FULL -> RegisterResult.Full
            else -> RegisterResult.ScheduleUnknown
        }

    // 읽는 사이 윈도우가 끝났거나 만료 시간이 없으면 윈도우 전체를 준다
    private fun retryAfterOf(remainingMillis: Long): Duration =
        if (remainingMillis > 0) Duration.ofMillis(remainingMillis) else policy.registerLimit.window

    override fun promote(
        eventId: Long,
        count: Int,
    ): List<String> {
        val result =
            redisTemplate.execute(
                promoteScript,
                promoteKeys(eventId),
                count.toString(),
                policy.activeTtl.toSeconds().toString(),
                eventId.toString(),
                policy.promotionBudgetPerSecond.toString(),
                BUDGET_KEY_TTL_SECONDS,
                budgetKeyPrefix,
                policy.cleanup.retention
                    .toSeconds()
                    .toString(),
            )
        @Suppress("UNCHECKED_CAST")
        return (result as? List<String>).orEmpty()
    }

    private fun promoteKeys(eventId: Long) =
        listOf(
            "queue:$eventId:waiting",
            "queue:$eventId:lastPromotedSeq",
            ACTIVE_EVENT_IDS_KEY,
            "queue:$eventId:seq",
        )

    override fun findStatus(
        eventId: Long,
        token: String,
    ): QueueStatus? {
        val keys =
            listOf(
                "queue:$eventId:token:$token",
                activeKey(eventId, token),
                "queue:$eventId:lastPromotedSeq",
                cleanedKey(eventId),
            )

        @Suppress("UNCHECKED_CAST")
        val result = redisTemplate.execute(statusScript, keys, policy.tokenTtl.toSeconds().toString()) as? List<Long> ?: return null
        if (result[0] != 1L) return null
        return QueueStatus(
            sequence = result[1],
            active = result[2] == 1L,
            lastPromotedSequence = result[3],
        )
    }

    override fun bindPhone(
        eventId: Long,
        token: String,
        phoneHash: PhoneHash,
    ): BindResult =
        when (redisTemplate.execute(bindScript, listOf(activeKey(eventId, token)), HexFormat.of().formatHex(phoneHash.value))) {
            BOUND -> BindResult.BOUND
            MISMATCH -> BindResult.MISMATCH
            else -> BindResult.NOT_ACTIVE
        }

    private fun activeKey(
        eventId: Long,
        token: String,
    ) = "queue:$eventId:active:$token"

    private fun cleanedKey(eventId: Long) = "queue:$eventId:cleaned"

    override fun cleanup(
        eventId: Long,
        notBefore: Instant,
    ): Boolean {
        val removed =
            redisTemplate.execute(
                cleanupScript,
                cleanupKeys(eventId),
                eventId.toString(),
                notBefore.toEpochMilli().toString(),
                policy.cleanup.retention
                    .toSeconds()
                    .toString(),
            ) ?: error("대기열 정리 실패 : 반환값 없음")
        return removed > 0
    }

    private fun cleanupKeys(eventId: Long) =
        listOf(
            "queue:$eventId:waiting",
            "queue:$eventId:seq",
            "queue:$eventId:lastPromotedSeq",
            ACTIVE_EVENT_IDS_KEY,
            cleanedKey(eventId),
        )

    override fun activeEventIds(): Set<Long> =
        redisTemplate
            .opsForSet()
            .members(ACTIVE_EVENT_IDS_KEY)
            .orEmpty()
            .map { it.toLong() }
            .toSet()

    companion object {
        const val DEFAULT_BUDGET_KEY_PREFIX = "queue:promotionBudget:"
        private const val BUDGET_KEY_TTL_SECONDS = "5"

        private val statusScript: RedisScript<List<*>> =
            DefaultRedisScript<List<*>>().apply {
                setLocation(ClassPathResource("scripts/queue-status.lua"))
                resultType = List::class.java
            }

        private const val ACTIVE_EVENT_IDS_KEY = "queue:activeEventIds"

        // queue-register.lua의 반환 코드
        private const val REGISTERED = 1L
        private const val NOT_OPEN = 0L
        private const val CLOSED = -2L
        private const val RATE_LIMITED = -3L
        private const val FULL = -4L

        private val registerScript: RedisScript<List<*>> =
            DefaultRedisScript<List<*>>().apply {
                setLocation(ClassPathResource("scripts/queue-register.lua"))
                resultType = List::class.java
            }

        private val promoteScript: RedisScript<List<*>> =
            DefaultRedisScript<List<*>>().apply {
                setLocation(ClassPathResource("scripts/queue-promote.lua"))
                resultType = List::class.java
            }

        private val cleanupScript: RedisScript<Long> =
            DefaultRedisScript<Long>().apply {
                setLocation(ClassPathResource("scripts/queue-cleanup.lua"))
                resultType = Long::class.java
            }

        // queue-bind.lua의 반환 코드
        private const val BOUND = 1L
        private const val MISMATCH = 0L

        private val bindScript: RedisScript<Long> =
            DefaultRedisScript<Long>().apply {
                setLocation(ClassPathResource("scripts/queue-bind.lua"))
                resultType = Long::class.java
            }
    }
}
