package com.ticketrush.queue.domain

import com.ticketrush.queue.QueuePolicyProperties
import com.ticketrush.support.IntegrationTest
import com.ticketrush.support.testClientIp
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test

class QueueRegisterGateTest : IntegrationTest() {
    @Autowired
    lateinit var queueRepository: QueueRepositoryPort

    @Autowired
    lateinit var redisTemplate: StringRedisTemplate

    @Autowired
    lateinit var policy: QueuePolicyProperties

    @Autowired
    lateinit var clock: Clock

    private fun token() = UUID.randomUUID().toString()

    private fun hoursFromNow(hours: Long) = Instant.ofEpochMilli(Instant.now(clock).plus(Duration.ofHours(hours)).toEpochMilli())

    private fun schedule(
        opensAt: Instant,
        startsAt: Instant = hoursFromNow(DAY_HOURS),
    ) = EventSchedule(opensAt = opensAt, startsAt = startsAt)

    @Test
    fun `공연 일정을 모르고 힌트도 없으면 ScheduleUnknown이고 순번을 쓰지 않는다`() {
        val eventId = System.nanoTime()

        val unknown = queueRepository.register(eventId, token(), testClientIp)
        val first = queueRepository.register(eventId, token(), testClientIp, schedule(Instant.EPOCH))

        assertThat(unknown).isEqualTo(RegisterResult.ScheduleUnknown)
        assertThat(first).isEqualTo(RegisterResult.Registered(1L))
    }

    @Test
    fun `오픈 시각이 과거면 등록된다`() {
        val eventId = System.nanoTime()

        val result = queueRepository.register(eventId, token(), testClientIp, schedule(hoursFromNow(-1)))

        assertThat(result).isEqualTo(RegisterResult.Registered(1L))
    }

    @Test
    fun `오픈 시각이 미래면 NotOpen이고 등록되지 않는다`() {
        val eventId = System.nanoTime()
        val token = token()
        val opensAt = hoursFromNow(1)

        val result = queueRepository.register(eventId, token, testClientIp, schedule(opensAt))

        assertThat(result).isEqualTo(RegisterResult.NotOpen(opensAt))
        assertThat(queueRepository.findStatus(eventId, token)).isNull()
        assertThat(queueRepository.activeEventIds()).doesNotContain(eventId)
    }

    @Test
    fun `힌트는 캐시에 저장되어 다음 호출은 힌트 없이도 판정된다`() {
        val eventId = System.nanoTime()
        val opensAt = hoursFromNow(1)
        queueRepository.register(eventId, token(), testClientIp, schedule(opensAt))

        val result = queueRepository.register(eventId, token(), testClientIp)

        assertThat(result).isEqualTo(RegisterResult.NotOpen(opensAt))
    }

    @Test
    fun `캐시가 있으면 새 힌트를 무시한다`() {
        val eventId = System.nanoTime()
        val opensAt = hoursFromNow(1)
        queueRepository.register(eventId, token(), testClientIp, schedule(opensAt))

        val result = queueRepository.register(eventId, token(), testClientIp, schedule(hoursFromNow(-1)))

        assertThat(result).isEqualTo(RegisterResult.NotOpen(opensAt))
    }

    @Test
    fun `공연 일정 캐시에는 TTL이 걸린다`() {
        val eventId = System.nanoTime()
        queueRepository.register(eventId, token(), testClientIp, schedule(hoursFromNow(1)))

        val opensAtTtl = redisTemplate.getExpire("queue:$eventId:opensAt")
        val startsAtTtl = redisTemplate.getExpire("queue:$eventId:startsAt")

        assertThat(opensAtTtl).isBetween(1L, policy.scheduleCacheTtl.seconds)
        assertThat(startsAtTtl).isBetween(1L, policy.scheduleCacheTtl.seconds)
    }

    @Test
    fun `공연 시작 시각이 지났으면 Closed이고 등록되지 않는다`() {
        val eventId = System.nanoTime()
        val token = token()
        val startsAt = hoursFromNow(-1)

        val result =
            queueRepository.register(
                eventId,
                token,
                testClientIp,
                schedule(opensAt = hoursFromNow(-DAY_HOURS), startsAt = startsAt),
            )

        assertThat(result).isEqualTo(RegisterResult.Closed(startsAt))
        assertThat(queueRepository.findStatus(eventId, token)).isNull()
        assertThat(queueRepository.activeEventIds()).doesNotContain(eventId)
    }

    @Test
    fun `캐시된 시작 시각으로 힌트 없이도 Closed로 판정된다`() {
        val eventId = System.nanoTime()
        val startsAt = hoursFromNow(-1)
        queueRepository.register(eventId, token(), testClientIp, schedule(opensAt = hoursFromNow(-DAY_HOURS), startsAt = startsAt))

        val result = queueRepository.register(eventId, token(), testClientIp)

        assertThat(result).isEqualTo(RegisterResult.Closed(startsAt))
    }

    private companion object {
        const val DAY_HOURS = 24L
    }
}
