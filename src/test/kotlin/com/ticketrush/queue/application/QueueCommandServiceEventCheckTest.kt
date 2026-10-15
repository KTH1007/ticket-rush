package com.ticketrush.queue.application

import com.ticketrush.queue.domain.EventSchedule
import com.ticketrush.queue.domain.EventSchedulePort
import com.ticketrush.queue.domain.QueueClosedException
import com.ticketrush.queue.domain.QueueEventNotFoundException
import com.ticketrush.queue.domain.QueueNotOpenException
import com.ticketrush.queue.domain.QueueRegisterRateLimitedException
import com.ticketrush.queue.domain.QueueRepositoryPort
import com.ticketrush.queue.domain.RegisterResult
import com.ticketrush.support.testClientIp
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.time.Duration
import java.time.Instant
import kotlin.test.Test

// 오픈, 시작 판정은 Redis 스크립트가 하고, 서비스는 공연 일정을 모를 때 DB에서 읽어 다시 등록하는 흐름만 맡는다
class QueueCommandServiceEventCheckTest {
    private val queueRepository = mockk<QueueRepositoryPort>()
    private val eventSchedule = mockk<EventSchedulePort>()
    private val service = QueueCommandService(queueRepository, eventSchedule)

    // 인증이 없는 등록이 몰릴 때 DB를 두드리지 않게, 공연 일정이 캐시돼 있으면 DB를 보지 않는다
    @Test
    fun `공연 일정을 알고 있으면 공연을 조회하지 않고 한 번에 등록한다`() {
        every { queueRepository.register(2L, any(), any()) } returns RegisterResult.Registered(5L)

        val token = service.register(2L, testClientIp)

        assertThat(token).isNotBlank()
        verify(exactly = 1) { queueRepository.register(2L, token, testClientIp) }
        verify(exactly = 0) { eventSchedule.schedule(any()) }
    }

    @Test
    fun `공연 일정을 모르면 DB에서 읽어 힌트로 같은 토큰을 다시 등록한다`() {
        val schedule = EventSchedule(opensAt = Instant.parse("2026-10-11T01:00:00Z"), startsAt = Instant.parse("2026-11-11T01:00:00Z"))
        every { queueRepository.register(3L, any(), any()) } returns RegisterResult.ScheduleUnknown
        every { eventSchedule.schedule(3L) } returns schedule
        every { queueRepository.register(3L, any(), any(), schedule) } returns RegisterResult.Registered(1L)

        val token = service.register(3L, testClientIp)

        verifyOrder {
            queueRepository.register(3L, token, testClientIp)
            eventSchedule.schedule(3L)
            queueRepository.register(3L, token, testClientIp, schedule)
        }
    }

    @Test
    fun `공연 일정을 모르는데 공연도 없으면 QueueEventNotFoundException을 던지고 다시 등록하지 않는다`() {
        every { queueRepository.register(404L, any(), any()) } returns RegisterResult.ScheduleUnknown
        every { eventSchedule.schedule(404L) } returns null

        assertThatThrownBy { service.register(404L, testClientIp) }.isInstanceOf(QueueEventNotFoundException::class.java)

        verify(exactly = 1) { queueRepository.register(404L, any(), any(), any()) }
    }

    @Test
    fun `오픈 전이면 서울 시각의 오픈 시각이 담긴 QueueNotOpenException을 던진다`() {
        every { queueRepository.register(10L, any(), any()) } returns RegisterResult.NotOpen(Instant.parse("2026-10-11T01:00:00Z"))

        assertThatThrownBy { service.register(10L, testClientIp) }
            .isInstanceOf(QueueNotOpenException::class.java)
            .hasMessageContaining("2026-10-11T10:00")
    }

    @Test
    fun `DB에서 읽은 오픈 시각으로 다시 등록해도 오픈 전이면 QueueNotOpenException을 던진다`() {
        val opensAt = Instant.parse("2026-10-11T03:00:00Z")
        val schedule = EventSchedule(opensAt = opensAt, startsAt = Instant.parse("2026-11-11T03:00:00Z"))
        every { queueRepository.register(11L, any(), any()) } returns RegisterResult.ScheduleUnknown
        every { eventSchedule.schedule(11L) } returns schedule
        every { queueRepository.register(11L, any(), any(), schedule) } returns RegisterResult.NotOpen(opensAt)

        assertThatThrownBy { service.register(11L, testClientIp) }
            .isInstanceOf(QueueNotOpenException::class.java)
            .hasMessageContaining("2026-10-11T12:00")
    }

    @Test
    fun `공연이 시작된 뒤면 서울 시각의 시작 시각이 담긴 QueueClosedException을 던진다`() {
        every { queueRepository.register(12L, any(), any()) } returns RegisterResult.Closed(Instant.parse("2026-10-11T01:00:00Z"))

        assertThatThrownBy { service.register(12L, testClientIp) }
            .isInstanceOf(QueueClosedException::class.java)
            .hasMessageContaining("2026-10-11T10:00")
    }

    @Test
    fun `IP의 발급 상한에 걸리면 남은 시간이 담긴 QueueRegisterRateLimitedException을 던진다`() {
        every { queueRepository.register(13L, any(), any()) } returns RegisterResult.RateLimited(Duration.ofSeconds(42))

        assertThatThrownBy { service.register(13L, testClientIp) }
            .isInstanceOfSatisfying(QueueRegisterRateLimitedException::class.java) {
                assertThat(it.code).isEqualTo("QUEUE_REGISTER_RATE_LIMITED")
                assertThat(it.retryAfter).isEqualTo(Duration.ofSeconds(42))
            }
    }
}
