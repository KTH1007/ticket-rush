package com.ticketrush.reservation.domain

import com.ticketrush.shared.PhoneHash
import java.time.LocalDateTime

interface SeatRepositoryPort : SeatQueryPort {
    fun save(seat: Seat): Seat

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
}
