package com.ticketrush.event.application

import com.ticketrush.event.SeatMapProperties
import com.ticketrush.event.domain.EventNotFoundException
import com.ticketrush.event.domain.EventQueryPort
import com.ticketrush.reservation.domain.SeatMapQueryPort
import com.ticketrush.reservation.domain.SeatStatus
import com.ticketrush.reservation.domain.SeatStatusRow
import com.ticketrush.support.TestClock
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test

class SeatStatusSnapshotCacheTest {
    private val seatMapQuery = mockk<SeatMapQueryPort>()
    private val eventQuery = mockk<EventQueryPort>()
    private val clock = TestClock()
    private val cache = SeatStatusSnapshotCache(seatMapQuery, eventQuery, clock, SeatMapProperties())
    private val rows = listOf(SeatStatusRow(0, SeatStatus.AVAILABLE), SeatStatusRow(1, SeatStatus.HELD))

    private fun 동시에_읽기(threads: Int): List<SeatStatusSnapshot> {
        val ready = CountDownLatch(threads)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        val futures =
            (1..threads).map {
                pool.submit<SeatStatusSnapshot> {
                    ready.countDown()
                    start.await()
                    cache.get(1L)
                }
            }
        ready.await()
        start.countDown()
        return futures.map { it.get(10, TimeUnit.SECONDS) }.also { pool.shutdown() }
    }

    @Test
    fun `처음 읽으면 만들고 신선한 동안은 다시 만들지 않는다`() {
        every { seatMapQuery.findStatusRowsByEventId(1L) } returns rows

        val first = cache.get(1L)
        clock.advanceSeconds(4)
        val second = cache.get(1L)

        assertThat(second).isSameAs(first)
        verify(exactly = 1) { seatMapQuery.findStatusRowsByEventId(1L) }
    }

    @Test
    fun `허용 시간보다 오래된 스냅샷을 읽으면 새로 만든다`() {
        every { seatMapQuery.findStatusRowsByEventId(1L) } returns rows
        val first = cache.get(1L)

        clock.advanceSeconds(6)
        val second = cache.get(1L)

        assertThat(second).isNotSameAs(first)
        assertThat(second.builtAt).isAfter(first.builtAt)
        verify(exactly = 2) { seatMapQuery.findStatusRowsByEventId(1L) }
    }

    @Test
    fun `스냅샷에는 좌석 수와 base64 비트맵이 담긴다`() {
        every { seatMapQuery.findStatusRowsByEventId(1L) } returns rows

        val snapshot = cache.get(1L)

        assertThat(snapshot.seatCount).isEqualTo(2)
        val expected = Base64.getEncoder().encodeToString(SeatStatusBitmap.encode(rows))
        assertThat(snapshot.bitmapBase64).isEqualTo(expected)
    }

    // 캐시가 비었을 때 몰린 요청이 모두 DB를 읽으면 오늘 측정한 병목이 그대로 재현된다
    @Test
    fun `20개 스레드가 동시에 읽어도 한 번만 만들고 모두 같은 스냅샷을 받는다`() {
        every { seatMapQuery.findStatusRowsByEventId(1L) } answers {
            Thread.sleep(100)
            rows
        }
        val snapshots = 동시에_읽기(threads = 20)

        assertThat(snapshots.toSet()).hasSize(1)
        verify(exactly = 1) { seatMapQuery.findStatusRowsByEventId(1L) }
    }

    // 인증 없는 조회가 없는 공연 번호마다 항목을 남기면 메모리가 늘어난다
    @Test
    fun `공연이 없으면 EventNotFoundException을 던지고 항목을 남기지 않는다`() {
        every { seatMapQuery.findStatusRowsByEventId(404L) } returns emptyList()
        every { eventQuery.existsById(404L) } returns false

        assertThatThrownBy { cache.get(404L) }.isInstanceOf(EventNotFoundException::class.java)

        assertThat(cache.cachedEventCount).isEqualTo(0)
    }

    @Test
    fun `좌석이 없어도 공연이 있으면 빈 스냅샷이다`() {
        every { seatMapQuery.findStatusRowsByEventId(2L) } returns emptyList()
        every { eventQuery.existsById(2L) } returns true

        val snapshot = cache.get(2L)

        assertThat(snapshot.seatCount).isEqualTo(0)
        assertThat(snapshot.bitmapBase64).isEmpty()
    }

    @Test
    fun `갱신은 최근 읽힌 공연만 다시 만든다`() {
        every { seatMapQuery.findStatusRowsByEventId(any()) } returns rows
        cache.get(1L)
        cache.get(2L)
        clock.advanceSeconds(4)
        cache.get(1L)
        clock.advanceSeconds(8)

        cache.refreshRecentlyRead()

        verify(exactly = 2) { seatMapQuery.findStatusRowsByEventId(1L) }
        verify(exactly = 1) { seatMapQuery.findStatusRowsByEventId(2L) }
    }

    @Test
    fun `오래 읽히지 않은 공연은 지운다`() {
        every { seatMapQuery.findStatusRowsByEventId(1L) } returns rows
        cache.get(1L)

        clock.advanceSeconds(61)
        cache.refreshRecentlyRead()

        assertThat(cache.cachedEventCount).isEqualTo(0)
    }

    @Test
    fun `갱신이 실패해도 예외를 던지지 않고 허용 시간 안에서는 이전 스냅샷을 준다`() {
        every { seatMapQuery.findStatusRowsByEventId(1L) } returns rows andThenThrows IllegalStateException("db down")
        val first = cache.get(1L)
        clock.advanceSeconds(3)

        assertThatCode { cache.refreshRecentlyRead() }.doesNotThrowAnyException()

        assertThat(cache.get(1L)).isSameAs(first)
    }

    @Test
    fun `실패가 이어져 허용 시간을 넘기면 읽는 쪽이 만들려다 예외가 난다`() {
        every { seatMapQuery.findStatusRowsByEventId(1L) } returns rows andThenThrows IllegalStateException("db down")
        cache.get(1L)
        clock.advanceSeconds(6)

        assertThatThrownBy { cache.get(1L) }.isInstanceOf(IllegalStateException::class.java)
    }

    // 스케줄러가 갱신 주기마다 틱을 줄 때 항목이 한 틱씩 밀려 2배 늦게 갱신되지 않아야 한다
    @Test
    fun `틱이 갱신 주기마다 오면 매 틱마다 다시 만든다`() {
        every { seatMapQuery.findStatusRowsByEventId(1L) } returns rows
        cache.get(1L)

        repeat(10) {
            clock.advanceSeconds(1)
            cache.get(1L)
            cache.refreshRecentlyRead()
        }

        verify(exactly = 11) { seatMapQuery.findStatusRowsByEventId(1L) }
    }

    // 읽는 쪽이 방금 만든 스냅샷을 곧바로 다시 만들면 같은 쿼리가 연달아 나간다
    @Test
    fun `읽는 쪽이 방금 만든 스냅샷은 다음 틱이 다시 만들지 않는다`() {
        every { seatMapQuery.findStatusRowsByEventId(1L) } returns rows
        cache.get(1L)

        clock.advanceMillis(400)
        cache.refreshRecentlyRead()

        verify(exactly = 1) { seatMapQuery.findStatusRowsByEventId(1L) }
    }
}
