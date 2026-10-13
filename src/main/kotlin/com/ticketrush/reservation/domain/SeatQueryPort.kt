package com.ticketrush.reservation.domain

import com.ticketrush.shared.PhoneHash

interface SeatQueryPort {
    fun findAllByEventId(eventId: Long): List<Seat>

    fun findAllByIds(seatIds: List<Long>): List<Seat>

    fun findAllByReservationId(reservationId: Long): List<Seat>

    // 1인 2매 제한 판정용. 이 전화번호가 이 공연에서 이미 쓴 slot 번호들을 반환한다.
    fun findHeldOrSoldSlotNos(
        eventId: Long,
        phoneHash: PhoneHash,
    ): List<Short>

    // 좌석이 있고 전부 SOLD이면 true. 좌석이 하나도 없는 공연은 매진이 아니다
    fun isFullySold(eventId: Long): Boolean
}
