package com.ticketrush.queue.application

import com.ticketrush.queue.domain.EventSchedulePort
import com.ticketrush.queue.domain.QueueEventNotFoundException
import com.ticketrush.queue.domain.QueueNotOpenException
import com.ticketrush.queue.domain.QueueRepositoryPort
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test

// 티켓팅은 같은 시각에 함께 들어와서 시작하므로, 오픈 전에는 줄을 세우지 않는다. 등록은 인증이 없어서 없는 이벤트 번호로 Redis 키가 남는 것도 막는다
class QueueCommandServiceEventCheckTest {
    private val queueRepository = mockk<QueueRepositoryPort>()
    private val eventSchedule = mockk<EventSchedulePort>()
    private val now = LocalDateTime.of(2026, 10, 11, 10, 0, 0)
    private val clock = Clock.fixed(now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
    private val service = QueueCommandService(queueRepository, eventSchedule, clock)

    @Test
    fun `존재하지 않는 이벤트면 QueueEventNotFoundException을 던지고 대기열에 등록하지 않는다`() {
        every { queueRepository.isActive(404L) } returns false
        every { eventSchedule.opensAt(404L) } returns null

        assertThatThrownBy { service.register(404L) }.isInstanceOf(QueueEventNotFoundException::class.java)

        verify(exactly = 0) { queueRepository.register(404L, any()) }
    }

    @Test
    fun `오픈 시각 전이면 QueueNotOpenException을 던지고 대기열에 등록하지 않는다`() {
        every { queueRepository.isActive(10L) } returns false
        every { eventSchedule.opensAt(10L) } returns now.plusSeconds(1)

        assertThatThrownBy { service.register(10L) }.isInstanceOf(QueueNotOpenException::class.java)

        verify(exactly = 0) { queueRepository.register(10L, any()) }
    }

    // 정각부터 열린다
    @Test
    fun `오픈 시각 정각이면 등록한다`() {
        every { queueRepository.isActive(11L) } returns false
        every { eventSchedule.opensAt(11L) } returns now
        every { queueRepository.register(11L, any()) } returns 1L

        val token = service.register(11L)

        assertThat(token).isNotBlank()
        verify(exactly = 1) { queueRepository.register(11L, token) }
    }

    @Test
    fun `오픈 시각이 지났으면 등록한다`() {
        every { queueRepository.isActive(12L) } returns false
        every { eventSchedule.opensAt(12L) } returns now.minusHours(1)
        every { queueRepository.register(12L, any()) } returns 1L

        service.register(12L)

        verify(exactly = 1) { queueRepository.register(12L, any()) }
    }

    // 이미 열린 이벤트는 오픈 시각 이후에 등록이 통과한 것이라 Redis만 보게 해서, 등록이 몰릴 때 DB를 두드리지 않게 한다
    @Test
    fun `대기열이 이미 열린 이벤트는 이벤트를 조회하지 않고 등록한다`() {
        every { queueRepository.isActive(2L) } returns true
        every { queueRepository.register(2L, any()) } returns 5L

        service.register(2L)

        verify(exactly = 0) { eventSchedule.opensAt(2L) }
        verify(exactly = 1) { queueRepository.register(2L, any()) }
    }

    @Test
    fun `오픈 전 응답에는 오픈 시각이 담긴다`() {
        every { queueRepository.isActive(13L) } returns false
        every { eventSchedule.opensAt(13L) } returns now.plusHours(2)

        assertThatThrownBy { service.register(13L) }
            .isInstanceOf(QueueNotOpenException::class.java)
            .hasMessageContaining(now.plusHours(2).toString())
    }
}
