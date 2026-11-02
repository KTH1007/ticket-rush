package com.ticketrush.queue.application

import com.ticketrush.queue.QueuePolicyProperties
import com.ticketrush.queue.domain.EventSchedule
import com.ticketrush.queue.domain.EventSchedulePort
import com.ticketrush.queue.domain.QueueRepositoryPort
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test

class QueueCleanupSchedulerTest {
    private val queueRepository = mockk<QueueRepositoryPort>()
    private val eventSchedulePort = mockk<EventSchedulePort>()
    private val now = Instant.parse("2026-11-01T12:00:00Z")
    private val policy =
        QueuePolicyProperties(
            tokenTtl = Duration.ofMinutes(15),
            activeTtl = Duration.ofMinutes(2),
            promotionBudgetPerSecond = 100,
            pollInterval = QueuePolicyProperties.PollInterval(far = Duration.ofSeconds(15), near = Duration.ofSeconds(2)),
            cleanup = QueuePolicyProperties.Cleanup(graceAfterStart = Duration.ofHours(1)),
        )
    private val scheduler = QueueCleanupScheduler(queueRepository, eventSchedulePort, policy, Clock.fixed(now, ZoneOffset.UTC))

    private fun 일정(startsAt: Instant) = EventSchedule(opensAt = startsAt.minus(Duration.ofDays(7)), startsAt = startsAt)

    @Test
    fun `시작 뒤 유예 시간이 지난 공연만 시작 시각에 유예를 더한 허용 시각으로 정리를 요청한다`() {
        // given
        every { queueRepository.activeEventIds() } returns setOf(1L, 2L)
        every { eventSchedulePort.schedule(1L) } returns 일정(now.minus(Duration.ofHours(2)))
        every { eventSchedulePort.schedule(2L) } returns 일정(now.minus(Duration.ofMinutes(30)))
        every { queueRepository.cleanup(any(), any()) } returns true

        // when
        scheduler.cleanup()

        // then
        verify(exactly = 1) { queueRepository.cleanup(1L, now.minus(Duration.ofHours(1))) }
        verify(exactly = 0) { queueRepository.cleanup(2L, any()) }
    }

    @Test
    fun `아직 시작하지 않은 공연은 정리하지 않는다`() {
        every { queueRepository.activeEventIds() } returns setOf(1L)
        every { eventSchedulePort.schedule(1L) } returns 일정(now.plus(Duration.ofDays(1)))

        scheduler.cleanup()

        verify(exactly = 0) { queueRepository.cleanup(any(), any()) }
    }

    @Test
    fun `일정을 모르는 공연은 건너뛴다`() {
        every { queueRepository.activeEventIds() } returns setOf(1L)
        every { eventSchedulePort.schedule(1L) } returns null

        scheduler.cleanup()

        verify(exactly = 0) { queueRepository.cleanup(any(), any()) }
    }

    @Test
    fun `한 공연의 정리가 실패해도 다른 공연은 정리한다`() {
        // given
        every { queueRepository.activeEventIds() } returns setOf(1L, 2L)
        every { eventSchedulePort.schedule(any()) } returns 일정(now.minus(Duration.ofHours(2)))
        every { queueRepository.cleanup(1L, any()) } throws IllegalStateException("Redis 장애")
        every { queueRepository.cleanup(2L, any()) } returns true

        // when
        scheduler.cleanup()

        // then
        verify(exactly = 1) { queueRepository.cleanup(2L, any()) }
    }
}
