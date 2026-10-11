package com.ticketrush.queue.application

import com.ticketrush.queue.domain.EventExistencePort
import com.ticketrush.queue.domain.QueueEventNotFoundException
import com.ticketrush.queue.domain.QueueRepositoryPort
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.test.Test

// 대기열 등록은 로그인 같은 확인이 없어서, 없는 이벤트 번호마다 Redis 키가 TTL 없이 남지 않게 등록 전에 이벤트를 확인한다
class QueueCommandServiceEventCheckTest {
    private val queueRepository = mockk<QueueRepositoryPort>()
    private val eventExistence = mockk<EventExistencePort>()
    private val service = QueueCommandService(queueRepository, eventExistence)

    @Test
    fun `존재하지 않는 이벤트면 QueueEventNotFoundException을 던지고 대기열에 등록하지 않는다`() {
        every { queueRepository.isActive(404L) } returns false
        every { eventExistence.exists(404L) } returns false

        assertThatThrownBy { service.register(404L) }.isInstanceOf(QueueEventNotFoundException::class.java)

        verify(exactly = 0) { queueRepository.register(404L, any()) }
    }

    @Test
    fun `대기열이 아직 열리지 않았어도 이벤트가 존재하면 등록한다`() {
        every { queueRepository.isActive(1L) } returns false
        every { eventExistence.exists(1L) } returns true
        every { queueRepository.register(1L, any()) } returns 1L

        val token = service.register(1L)

        assertThat(token).isNotBlank()
        verify(exactly = 1) { queueRepository.register(1L, token) }
    }

    // 이미 등록이 통과해 열린 이벤트는 Redis만 보게 해서, 등록이 몰릴 때 DB를 두드리지 않게 한다
    @Test
    fun `대기열이 이미 열린 이벤트는 이벤트를 조회하지 않고 등록한다`() {
        every { queueRepository.isActive(2L) } returns true
        every { queueRepository.register(2L, any()) } returns 5L

        service.register(2L)

        verify(exactly = 0) { eventExistence.exists(2L) }
        verify(exactly = 1) { queueRepository.register(2L, any()) }
    }
}
