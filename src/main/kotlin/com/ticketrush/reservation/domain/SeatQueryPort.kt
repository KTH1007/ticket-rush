package com.ticketrush.reservation.domain

import com.ticketrush.shared.PhoneHash

interface SeatQueryPort {
    fun findAllByEventId(eventId: Long): List<Seat>

    fun findAllByIds(seatIds: List<Long>): List<Seat>

    // 1인 2매 제한 판정용. 이 전화번호가 이 공연에서 이미 쓴 slot 번호들을 반환한다.
    fun findHeldOrSoldSlotNos(
        eventId: Long,
        phoneHash: PhoneHash,
    ): List<Short>
}
