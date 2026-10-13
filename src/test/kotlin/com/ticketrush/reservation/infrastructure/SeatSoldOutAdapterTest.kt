package com.ticketrush.reservation.infrastructure

import com.ticketrush.queue.QueuePolicyProperties
import com.ticketrush.reservation.domain.SeatQueryPort
import com.ticketrush.support.TestClock
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import java.time.Duration
import kotlin.test.Test

class SeatSoldOutAdapterTest {
    private val seatQueryPort = mockk<SeatQueryPort>()
    private val clock = TestClock()

    private fun adapter(cacheTtl: Duration) =
        SeatSoldOutAdapter(
            seatQueryPort,
            clock,
            QueuePolicyProperties(
                tokenTtl = Duration.ofMinutes(15),
                activeTtl = Duration.ofMinutes(2),
                promotionBudgetPerSecond = 100,
                pollInterval = QueuePolicyProperties.PollInterval(far = Duration.ofSeconds(15), near = Duration.ofSeconds(2)),
                broadcastCacheTtl = cacheTtl,
            ),
        )

    @Test
    fun `캐시 시간 안에는 DB를 다시 보지 않고 지나면 다시 본다`() {
        every { seatQueryPort.isFullySold(1L) } returns false
        val adapter = adapter(Duration.ofSeconds(1))

        assertThat(adapter.isSoldOut(1L)).isFalse()
        every { seatQueryPort.isFullySold(1L) } returns true
        assertThat(adapter.isSoldOut(1L)).isFalse()
        verify(exactly = 1) { seatQueryPort.isFullySold(1L) }

        clock.advanceMillis(1_001)

        assertThat(adapter.isSoldOut(1L)).isTrue()
        verify(exactly = 2) { seatQueryPort.isFullySold(1L) }
    }

    @Test
    fun `캐시 시간이 0이면 매번 DB를 본다`() {
        every { seatQueryPort.isFullySold(1L) } returns false
        val adapter = adapter(Duration.ZERO)

        repeat(3) { adapter.isSoldOut(1L) }

        verify(exactly = 3) { seatQueryPort.isFullySold(1L) }
    }

    @Test
    fun `공연마다 따로 캐시한다`() {
        every { seatQueryPort.isFullySold(1L) } returns true
        every { seatQueryPort.isFullySold(2L) } returns false
        val adapter = adapter(Duration.ofSeconds(1))

        assertThat(adapter.isSoldOut(1L)).isTrue()
        assertThat(adapter.isSoldOut(2L)).isFalse()
    }
}
