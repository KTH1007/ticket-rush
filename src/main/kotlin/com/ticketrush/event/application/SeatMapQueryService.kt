package com.ticketrush.event.application

import com.ticketrush.event.presentation.SeatStatusResponse
import org.springframework.stereotype.Service

// 트랜잭션을 열지 않는다. 읽기 요청이 DB 커넥션을 쥐지 않는 것이 이 구조의 목적이다
@Service
class SeatMapQueryService(
    private val statusCache: SeatStatusSnapshotCache,
    private val layoutCache: SeatLayoutCache,
) {
    fun findStatus(eventId: Long): SeatStatusResponse {
        val snapshot = statusCache.get(eventId)
        return SeatStatusResponse(
            eventId = snapshot.eventId,
            seatCount = snapshot.seatCount,
            bitsPerSeat = SeatStatusBitmap.BITS_PER_SEAT,
            builtAt = snapshot.builtAt,
            bitmap = snapshot.bitmapBase64,
        )
    }

    fun findLayout(eventId: Long): SeatLayoutSnapshot = layoutCache.get(eventId)
}
