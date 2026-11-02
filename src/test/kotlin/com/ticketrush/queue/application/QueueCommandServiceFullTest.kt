package com.ticketrush.queue.application

import com.ticketrush.queue.domain.EventSchedulePort
import com.ticketrush.queue.domain.QueueFullException
import com.ticketrush.queue.domain.QueueRepositoryPort
import com.ticketrush.queue.domain.RegisterResult
import com.ticketrush.support.testClientIp
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.time.Duration
import kotlin.test.Test

class QueueCommandServiceFullTest {
    private val queueRepository = mockk<QueueRepositoryPort>()
    private val meterRegistry = SimpleMeterRegistry()
    private val service = QueueCommandService(queueRepository, mockk<EventSchedulePort>(), meterRegistry)

    @Test
    fun `대기열이 가득 차면 5초에서 10초 사이의 Retry-After와 함께 거절한다`() {
        every { queueRepository.register(1L, any(), any()) } returns RegisterResult.Full

        assertThatThrownBy { service.register(1L, testClientIp) }
            .isInstanceOf(QueueFullException::class.java)
            .satisfies({ e ->
                val retryAfter = (e as QueueFullException).retryAfter!!
                assertThat(retryAfter).isBetween(Duration.ofSeconds(5), Duration.ofSeconds(10))
            })
    }

    // 장애로 인한 503과 구분하려고 의도된 거절 횟수를 따로 센다
    @Test
    fun `가득 차서 거절한 횟수를 사유 태그와 함께 센다`() {
        every { queueRepository.register(1L, any(), any()) } returns RegisterResult.Full

        repeat(3) { runCatching { service.register(1L, testClientIp) } }

        assertThat(meterRegistry.counter("queue.register.rejected", "reason", "full").count()).isEqualTo(3.0)
    }

    @Test
    fun `등록되면 거절 횟수를 올리지 않는다`() {
        every { queueRepository.register(1L, any(), any()) } returns RegisterResult.Registered(1L)

        service.register(1L, testClientIp)

        assertThat(meterRegistry.counter("queue.register.rejected", "reason", "full").count()).isZero()
    }
}
