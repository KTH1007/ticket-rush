package com.ticketrush.reservation.domain

import com.ticketrush.shared.PhoneHash
import java.time.LocalDateTime

interface SeatRepositoryPort {
    fun save(seat: Seat): Seat

    fun findAllByEventId(eventId: Long): List<Seat>

    // 1인 2매 제한 판정용. 이 전화번호가 이 공연에서 이미 쓴 slot 번호들을 반환한다.
    fun findHeldOrSoldSlotNos(
        eventId: Long,
        phoneHash: PhoneHash,
    ): List<Short>

    // 좌석 하나를 조건부로 홀드한다. slot 배정은 호출자(애플리케이션 서비스)가 이미
    // 끝낸 상태로 넘어온다 — 여러 좌석을 한 번에 묶는 all-or-nothing 판단도 호출자 몫이다.
    fun holdIfAvailable(
        eventId: Long,
        seatId: Long,
        reservationId: Long,
        phoneHash: PhoneHash,
        slotNo: Short,
        holdExpiresAt: LocalDateTime,
    ): Boolean

    fun findAllByIds(seatIds: List<Long>): List<Seat>
}
