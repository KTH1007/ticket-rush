package com.ticketrush.event.application

import com.ticketrush.event.SeatMapProperties
import com.ticketrush.event.domain.EventNotFoundException
import com.ticketrush.event.domain.EventQueryPort
import com.ticketrush.reservation.domain.Grade
import com.ticketrush.reservation.domain.GradeQueryPort
import com.ticketrush.reservation.domain.SeatLayoutRow
import com.ticketrush.reservation.domain.SeatMapQueryPort
import com.ticketrush.support.TestClock
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test

class SeatLayoutCacheTest {
    private val seatMapQuery = mockk<SeatMapQueryPort>()
    private val gradeQuery = mockk<GradeQueryPort>()
    private val eventQuery = mockk<EventQueryPort>()
    private val jsonMapper = JsonMapper.builder().build()
    private val clock = TestClock()
    private val cache = SeatLayoutCache(seatMapQuery, gradeQuery, eventQuery, jsonMapper, clock, SeatMapProperties())
    private val vip = Grade(id = 7L, eventId = 1L, name = "VIP", price = 200_000)
    private val rows =
        listOf(
            SeatLayoutRow(id = 100L, ordinal = 0, section = "A", rowLabel = "1", seatNo = 1, gradeId = 7L),
            SeatLayoutRow(id = 101L, ordinal = 1, section = "A", rowLabel = "1", seatNo = 2, gradeId = 7L),
        )

    private fun givenEvent(layoutRows: List<SeatLayoutRow> = rows) {
        every { seatMapQuery.findLayoutRowsByEventId(1L) } returns layoutRows
        every { gradeQuery.findAllByEventId(1L) } returns listOf(vip)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parse(snapshot: SeatLayoutSnapshot): Map<String, Any> =
        jsonMapper.readValue(snapshot.json(), Map::class.java) as Map<String, Any>

    private fun 동시에_읽기(threads: Int): List<SeatLayoutSnapshot> {
        val ready = CountDownLatch(threads)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        val futures =
            (1..threads).map {
                pool.submit<SeatLayoutSnapshot> {
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
    fun `배치에는 등급 표와 ordinal 순서의 좌석이 담긴다`() {
        givenEvent()

        val layout = parse(cache.get(1L))

        assertThat(layout["eventId"]).isEqualTo(1)
        assertThat(layout["seatCount"]).isEqualTo(2)
        assertThat(layout["grades"] as List<*>).containsExactly(mapOf("id" to 7, "name" to "VIP"))
        assertThat(layout["seats"] as List<*>).containsExactly(
            mapOf("id" to 100, "ordinal" to 0, "section" to "A", "rowLabel" to "1", "seatNo" to 1, "gradeId" to 7),
            mapOf("id" to 101, "ordinal" to 1, "section" to "A", "rowLabel" to "1", "seatNo" to 2, "gradeId" to 7),
        )
    }

    @Test
    fun `유효 시간 안에서는 다시 만들지 않고 지나면 다시 만든다`() {
        givenEvent()
        val first = cache.get(1L)
        clock.advanceMinutes(9)

        val withinTtl = cache.get(1L)
        clock.advanceMinutes(2)
        val afterTtl = cache.get(1L)

        assertThat(withinTtl).isSameAs(first)
        assertThat(afterTtl).isNotSameAs(first)
        verify(exactly = 2) { seatMapQuery.findLayoutRowsByEventId(1L) }
    }

    @Test
    fun `20개 스레드가 동시에 읽어도 한 번만 만들고 모두 같은 배치를 받는다`() {
        every { seatMapQuery.findLayoutRowsByEventId(1L) } answers {
            Thread.sleep(100)
            rows
        }
        every { gradeQuery.findAllByEventId(1L) } returns listOf(vip)

        val snapshots = 동시에_읽기(threads = 20)

        assertThat(snapshots.toSet()).hasSize(1)
        verify(exactly = 1) { seatMapQuery.findLayoutRowsByEventId(1L) }
    }

    @Test
    fun `내용이 같으면 다시 만들어도 같은 ETag이고 내용이 바뀌면 다른 ETag이다`() {
        givenEvent()
        val first = cache.get(1L)

        clock.advanceMinutes(11)
        val rebuiltSame = cache.get(1L)
        givenEvent(rows + SeatLayoutRow(id = 102L, ordinal = 2, section = "A", rowLabel = "1", seatNo = 3, gradeId = 7L))
        clock.advanceMinutes(11)
        val rebuiltChanged = cache.get(1L)

        assertThat(rebuiltSame.etag).isEqualTo(first.etag)
        assertThat(rebuiltChanged.etag).isNotEqualTo(first.etag)
    }

    // 배치는 안 바뀌는 데이터라 DB가 잠깐 안 돼도 이전 배치를 주는 편이 에러보다 낫다
    @Test
    fun `다시 만들다 실패하면 이전 배치를 주고 잠시 동안은 다시 시도하지 않는다`() {
        every { seatMapQuery.findLayoutRowsByEventId(1L) } returns rows andThenThrows IllegalStateException("db down")
        every { gradeQuery.findAllByEventId(1L) } returns listOf(vip)
        val first = cache.get(1L)
        clock.advanceMinutes(11)

        val failed = cache.get(1L)
        clock.advanceSeconds(3)
        val duringBackoff = cache.get(1L)

        assertThat(failed).isSameAs(first)
        assertThat(duringBackoff).isSameAs(first)
        verify(exactly = 2) { seatMapQuery.findLayoutRowsByEventId(1L) }
    }

    @Test
    fun `이전 배치가 없는데 만들기에 실패하면 예외가 난다`() {
        every { seatMapQuery.findLayoutRowsByEventId(1L) } throws IllegalStateException("db down")

        assertThatThrownBy { cache.get(1L) }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `공연이 없으면 EventNotFoundException을 던지고 항목을 남기지 않는다`() {
        every { seatMapQuery.findLayoutRowsByEventId(404L) } returns emptyList()
        every { eventQuery.existsById(404L) } returns false

        assertThatThrownBy { cache.get(404L) }.isInstanceOf(EventNotFoundException::class.java)

        assertThat(cache.cachedEventCount).isEqualTo(0)
    }

    @Test
    fun `오래 읽히지 않은 배치는 지운다`() {
        givenEvent()
        cache.get(1L)

        clock.advanceMinutes(11)
        cache.evictIdle()

        assertThat(cache.cachedEventCount).isEqualTo(0)
    }
}
