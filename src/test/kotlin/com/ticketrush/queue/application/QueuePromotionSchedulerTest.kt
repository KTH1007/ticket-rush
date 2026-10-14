package com.ticketrush.queue.application

import com.ticketrush.queue.QueuePolicyProperties
import com.ticketrush.queue.domain.QueueRepositoryPort
import com.ticketrush.queue.domain.SoldOutPort
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import java.time.Duration
import kotlin.test.Test

class QueuePromotionSchedulerTest {
    private val queueRepository = mockk<QueueRepositoryPort>()
    private val soldOutPort = mockk<SoldOutPort> { every { isSoldOut(any()) } returns false }
    private val policy =
        QueuePolicyProperties(
            tokenTtl = Duration.ofMinutes(15),
            activeTtl = Duration.ofMinutes(2),
            promotionBudgetPerSecond = 100,
            pollInterval = QueuePolicyProperties.PollInterval(far = Duration.ofSeconds(15), near = Duration.ofSeconds(2)),
        )
    private val scheduler = QueuePromotionScheduler(queueRepository, policy, soldOutPort)

    @Test
    fun `활성 공연마다 한 번씩 초당 예산을 최대치로 승격을 요청한다`() {
        every { queueRepository.activeEventIds() } returns setOf(1L, 2L, 3L)
        every { queueRepository.promote(any(), any()) } returns emptyList()

        scheduler.promote()

        listOf(1L, 2L, 3L).forEach { verify(exactly = 1) { queueRepository.promote(it, 100) } }
    }

    // 예산이 서비스 전체에 하나라, 순서가 고정이면 앞 공연이 예산을 다 가져가 뒤 공연이 굶는다
    @Test
    fun `틱마다 공연 순서가 섞여 처음 요청받는 공연이 고정되지 않는다`() {
        val ticks = 50
        val calls = mutableListOf<Long>()
        every { queueRepository.activeEventIds() } returns setOf(1L, 2L, 3L)
        every { queueRepository.promote(any(), any()) } answers {
            calls += firstArg<Long>()
            emptyList()
        }

        repeat(ticks) { scheduler.promote() }

        val firstOfEachTick = (0 until ticks).map { calls[it * 3] }.toSet()
        assertThat(firstOfEachTick.size).isGreaterThan(1)
    }

    @Test
    fun `매진된 공연은 승격하지 않고 나머지만 승격한다`() {
        every { queueRepository.activeEventIds() } returns setOf(1L, 2L)
        every { soldOutPort.isSoldOut(1L) } returns true
        every { queueRepository.promote(any(), any()) } returns emptyList()

        scheduler.promote()

        verify(exactly = 0) { queueRepository.promote(1L, any()) }
        verify(exactly = 1) { queueRepository.promote(2L, 100) }
    }

    @Test
    fun `활성 공연이 없으면 승격을 요청하지 않는다`() {
        every { queueRepository.activeEventIds() } returns emptySet()

        scheduler.promote()

        verify(exactly = 0) { queueRepository.promote(any(), any()) }
    }
}
