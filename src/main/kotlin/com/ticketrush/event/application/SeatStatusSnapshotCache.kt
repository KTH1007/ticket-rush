package com.ticketrush.event.application

import com.ticketrush.event.SeatMapProperties
import com.ticketrush.event.domain.EventNotFoundException
import com.ticketrush.event.domain.EventQueryPort
import com.ticketrush.reservation.domain.SeatMapQueryPort
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private val logger = KotlinLogging.logger {}

// 읽기 요청이 DB를 쓰지 않도록 공연별 최신 스냅샷을 앱 메모리에 둔다. 인스턴스끼리 공유하지 않는다
@Component
class SeatStatusSnapshotCache(
    private val seatMapQuery: SeatMapQueryPort,
    private val eventQuery: EventQueryPort,
    private val clock: Clock,
    private val props: SeatMapProperties,
) {
    private val entries = ConcurrentHashMap<Long, Entry>()

    internal val cachedEventCount: Int get() = entries.size

    fun get(eventId: Long): SeatStatusSnapshot {
        val entry = entries.computeIfAbsent(eventId) { Entry() }
        entry.lastReadAtMillis = clock.millis()
        entry.snapshot?.takeIf(::isFresh)?.let { return it }
        // 캐시가 비었거나 오래됐을 때 몰린 요청이 모두 DB를 읽지 않도록 한 요청만 만들고 나머지는 기다린다
        return entry.lock.withLock { entry.snapshot?.takeIf(::isFresh) ?: build(eventId, entry) }
    }

    // 스케줄러가 부른다. 최근 읽힌 공연만 순차로 다시 만들고, 오래 안 읽힌 공연은 지운다
    fun refreshRecentlyRead() {
        entries.forEach { (eventId, entry) ->
            val idleMillis = clock.millis() - entry.lastReadAtMillis
            when {
                idleMillis > props.statusEvictAfter.toMillis() -> entries.remove(eventId, entry)
                idleMillis <= props.statusActiveWindow.toMillis() -> refresh(eventId, entry)
            }
        }
    }

    // 갱신 하나가 실패해도 다음 틱과 다른 공연은 계속 돌아야 하고, 읽는 쪽은 허용 시간 안에서 이전 스냅샷을 쓴다
    @Suppress("TooGenericExceptionCaught")
    private fun refresh(
        eventId: Long,
        entry: Entry,
    ) {
        val snapshot = entry.snapshot ?: return
        // 읽는 쪽이 방금 만든 스냅샷을 곧바로 다시 만들지 않으려는 하한이다. 틱 주기와 같은 값을 임계값으로 두면 한 틱씩 밀린다
        if (clock.millis() - snapshot.builtAt.toEpochMilli() < props.statusRefreshInterval.toMillis() / 2) return
        // 읽는 쪽이 만들고 있으면 그 결과를 쓰면 되므로 기다리지 않는다
        if (!entry.lock.tryLock()) return
        try {
            build(eventId, entry)
        } catch (e: Exception) {
            logger.warn(e) { "좌석 상태 스냅샷 갱신에 실패했습니다: eventId=$eventId" }
        } finally {
            entry.lock.unlock()
        }
    }

    private fun build(
        eventId: Long,
        entry: Entry,
    ): SeatStatusSnapshot {
        val rows = seatMapQuery.findStatusRowsByEventId(eventId)
        if (rows.isEmpty() && !eventQuery.existsById(eventId)) {
            entries.remove(eventId, entry)
            throw EventNotFoundException(eventId)
        }
        val snapshot =
            SeatStatusSnapshot(
                eventId = eventId,
                builtAt = clock.instant(),
                seatCount = SeatStatusBitmap.seatCount(rows),
                bitmapBase64 = Base64.getEncoder().encodeToString(SeatStatusBitmap.encode(rows)),
            )
        entry.snapshot = snapshot
        return snapshot
    }

    private fun isFresh(snapshot: SeatStatusSnapshot): Boolean =
        clock.millis() - snapshot.builtAt.toEpochMilli() <= props.statusMaxStale.toMillis()

    private class Entry {
        @Volatile var snapshot: SeatStatusSnapshot? = null

        @Volatile var lastReadAtMillis: Long = 0
        val lock = ReentrantLock()
    }
}
