package com.ticketrush.queue.application

import com.ticketrush.queue.domain.EventSchedulePort
import com.ticketrush.queue.domain.QueueEventNotFoundException
import com.ticketrush.queue.domain.QueueNotOpenException
import com.ticketrush.queue.domain.QueueRepositoryPort
import com.ticketrush.queue.domain.RegisterResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.time.Instant
import kotlin.test.Test

// 오픈 판정은 Redis 스크립트가 하고, 서비스는 오픈 시각을 모를 때 DB에서 읽어 다시 등록하는 흐름만 맡는다
class QueueCommandServiceEventCheckTest {
    private val queueRepository = mockk<QueueRepositoryPort>()
    private val eventSchedule = mockk<EventSchedulePort>()
    private val service = QueueCommandService(queueRepository, eventSchedule)

    // 인증이 없는 등록이 몰릴 때 DB를 두드리지 않게, 오픈 시각이 캐시돼 있으면 DB를 보지 않는다
    @Test
    fun `오픈 시각을 알고 있으면 공연을 조회하지 않고 한 번에 등록한다`() {
        every { queueRepository.register(2L, any()) } returns RegisterResult.Registered(5L)

        val token = service.register(2L)

        assertThat(token).isNotBlank()
        verify(exactly = 1) { queueRepository.register(2L, token) }
        verify(exactly = 0) { eventSchedule.opensAt(any()) }
    }

    @Test
    fun `오픈 시각을 모르면 DB에서 읽어 힌트로 같은 토큰을 다시 등록한다`() {
        val opensAt = Instant.parse("2026-10-11T01:00:00Z")
        every { queueRepository.register(3L, any()) } returns RegisterResult.ScheduleUnknown
        every { eventSchedule.opensAt(3L) } returns opensAt
        every { queueRepository.register(3L, any(), opensAt) } returns RegisterResult.Registered(1L)

        val token = service.register(3L)

        verifyOrder {
            queueRepository.register(3L, token)
            eventSchedule.opensAt(3L)
            queueRepository.register(3L, token, opensAt)
        }
    }

    @Test
    fun `오픈 시각을 모르는데 공연도 없으면 QueueEventNotFoundException을 던지고 다시 등록하지 않는다`() {
        every { queueRepository.register(404L, any()) } returns RegisterResult.ScheduleUnknown
        every { eventSchedule.opensAt(404L) } returns null

        assertThatThrownBy { service.register(404L) }.isInstanceOf(QueueEventNotFoundException::class.java)

        verify(exactly = 1) { queueRepository.register(404L, any(), any()) }
    }

    @Test
    fun `오픈 전이면 서울 시각의 오픈 시각이 담긴 QueueNotOpenException을 던진다`() {
        every { queueRepository.register(10L, any()) } returns RegisterResult.NotOpen(Instant.parse("2026-10-11T01:00:00Z"))

        assertThatThrownBy { service.register(10L) }
            .isInstanceOf(QueueNotOpenException::class.java)
            .hasMessageContaining("2026-10-11T10:00")
    }

    @Test
    fun `DB에서 읽은 오픈 시각으로 다시 등록해도 오픈 전이면 QueueNotOpenException을 던진다`() {
        val opensAt = Instant.parse("2026-10-11T03:00:00Z")
        every { queueRepository.register(11L, any()) } returns RegisterResult.ScheduleUnknown
        every { eventSchedule.opensAt(11L) } returns opensAt
        every { queueRepository.register(11L, any(), opensAt) } returns RegisterResult.NotOpen(opensAt)

        assertThatThrownBy { service.register(11L) }
            .isInstanceOf(QueueNotOpenException::class.java)
            .hasMessageContaining("2026-10-11T12:00")
    }
}
