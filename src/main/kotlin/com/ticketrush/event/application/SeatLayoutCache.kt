package com.ticketrush.event.application

import com.ticketrush.event.SeatMapProperties
import com.ticketrush.event.domain.EventNotFoundException
import com.ticketrush.event.domain.EventQueryPort
import com.ticketrush.event.presentation.SeatLayoutResponse
import com.ticketrush.reservation.domain.GradeQueryPort
import com.ticketrush.reservation.domain.SeatMapQueryPort
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private val logger = KotlinLogging.logger {}

// 새 사용자가 몰려 모두 좌석 배치를 받아도 요청마다 3만 좌석을 직렬화하지 않도록 미리 만든 바이트를 공연별로 둔다
@Component
class SeatLayoutCache(
    private val seatMapQuery: SeatMapQueryPort,
    private val gradeQuery: GradeQueryPort,
    private val eventQuery: EventQueryPort,
    private val jsonMapper: ObjectMapper,
    private val clock: Clock,
    private val props: SeatMapProperties,
) {
    private val entries = ConcurrentHashMap<Long, Entry>()

    internal val cachedEventCount: Int get() = entries.size

    fun get(eventId: Long): SeatLayoutSnapshot {
        val entry = entries.computeIfAbsent(eventId) { Entry() }
        entry.lastReadAtMillis = clock.millis()
        usable(entry)?.let { return it }
        // 캐시가 비었거나 만료됐을 때 몰린 요청이 모두 만들지 않도록 한 요청만 만들고 나머지는 기다린다
        return entry.lock.withLock { usable(entry) ?: rebuild(eventId, entry) }
    }

    // 오래 읽히지 않은 배치는 지워서 공연이 많아도 힙을 넘지 않게 한다
    fun evictIdle() {
        val now = clock.millis()
        entries.entries.removeIf { now - it.value.lastReadAtMillis > props.layoutTtl.toMillis() }
    }

    // 유효하거나, 최근 갱신에 실패해 잠시 다시 시도하지 않는 동안의 이전 배치
    private fun usable(entry: Entry): SeatLayoutSnapshot? {
        val snapshot = entry.snapshot ?: return null
        val now = clock.millis()
        val fresh = now - snapshot.builtAt.toEpochMilli() <= props.layoutTtl.toMillis()
        return snapshot.takeIf { fresh || now < entry.retryAfterMillis }
    }

    // 배치는 안 바뀌는 데이터라 DB가 잠깐 안 돼도 이전 배치를 주는 편이 에러보다 낫다. 요청마다 다시 시도하지 않게 잠시 쉰다
    @Suppress("TooGenericExceptionCaught")
    private fun rebuild(
        eventId: Long,
        entry: Entry,
    ): SeatLayoutSnapshot =
        try {
            build(eventId).also { entry.snapshot = it }
        } catch (e: EventNotFoundException) {
            entries.remove(eventId, entry)
            throw e
        } catch (e: Exception) {
            val previous = entry.snapshot ?: throw e
            entry.retryAfterMillis = clock.millis() + RETRY_BACKOFF_MILLIS
            logger.warn(e) { "좌석 배치를 다시 만들지 못해 이전 배치를 사용합니다: eventId=$eventId" }
            previous
        }

    private fun build(eventId: Long): SeatLayoutSnapshot {
        val rows = seatMapQuery.findLayoutRowsByEventId(eventId)
        if (rows.isEmpty() && !eventQuery.existsById(eventId)) throw EventNotFoundException(eventId)
        val response =
            SeatLayoutResponse(
                eventId = eventId,
                seatCount = rows.maxOfOrNull { it.ordinal + 1 } ?: 0,
                grades = gradeQuery.findAllByEventId(eventId).map { SeatLayoutResponse.GradeItem(it.id, it.name) },
                seats = rows.map { SeatLayoutResponse.SeatItem(it.id, it.ordinal, it.section, it.rowLabel, it.seatNo, it.gradeId) },
            )
        return SeatLayoutSnapshot.from(eventId, clock.instant(), jsonMapper.writeValueAsBytes(response))
    }

    private class Entry {
        @Volatile var snapshot: SeatLayoutSnapshot? = null

        @Volatile var lastReadAtMillis: Long = 0

        @Volatile var retryAfterMillis: Long = 0
        val lock = ReentrantLock()
    }

    private companion object {
        const val RETRY_BACKOFF_MILLIS = 5_000L
    }
}
