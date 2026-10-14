package com.ticketrush.reservation.domain

import java.util.UUID

// 커밋 뒤 Redis 마크를 맞추는 데 필요한 값. 취소 뒤에는 좌석이 예약 id를 잃어 조회할 수 없어 좌석 id를 미리 담는다
data class SeatsSoldEvent(
    val eventId: Long,
    val seatIds: List<Long>,
    val holdToken: UUID,
)
