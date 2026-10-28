package com.ticketrush.reservation.application

import com.ticketrush.reservation.domain.SeatHoldFilterPort
import com.ticketrush.reservation.domain.SeatHoldShortenedEvent
import com.ticketrush.reservation.domain.SeatMarkSyncPort
import com.ticketrush.reservation.domain.SeatsReturnedEvent
import com.ticketrush.reservation.domain.SeatsSoldEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

// DB 커밋 뒤에 호출돼 Redis 마크를 좌석 상태에 맞춘다. 실패해도 TTL이 마지막 안전망이라 로그만 남긴다
@Component
class SeatMarkSynchronizer(
    private val seatHoldFilter: SeatHoldFilterPort,
) : SeatMarkSyncPort {
    override fun onSold(event: SeatsSoldEvent) =
        sync("판매", event.eventId, event.seatIds) {
            seatHoldFilter.extendForSold(event.eventId, event.seatIds, event.holdToken.toString())
        }

    override fun onHoldShortened(event: SeatHoldShortenedEvent) =
        sync("홀드 단축", event.eventId, event.seatIds) {
            seatHoldFilter.shortenAfterPaymentFailure(event.eventId, event.seatIds, event.holdToken.toString())
        }

    override fun onReturned(event: SeatsReturnedEvent) =
        sync("반환", event.eventId, event.seatIds) {
            seatHoldFilter.release(event.eventId, event.seatIds, event.holdToken.toString())
        }

    @Suppress("TooGenericExceptionCaught")
    private fun sync(
        action: String,
        eventId: Long,
        seatIds: List<Long>,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (e: Exception) {
            logger.error(e) { "좌석 마크 $action 동기화 실패. eventId=$eventId, seatIds=$seatIds - TTL 만료 전까지 마크가 실제 상태와 다를 수 있음" }
        }
    }
}
