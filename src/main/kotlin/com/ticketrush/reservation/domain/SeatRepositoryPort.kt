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

    // 만료된 홀드를 일괄 해제 (스케줄러 전용)
    fun releaseExpiredHolds(now: LocalDateTime): Int

    // 결제 확정 시 그 예약에 속한 HELD 좌석을 전부 SOLD로 전환한다.
    fun markSold(reservationId: Long): Int

    // 결제 실패 시 그 예약에 속한 HELD 좌석의 홀드 만료 시각을 단축한다(재시도 창 축소).
    fun shortenHoldExpiry(
        reservationId: Long,
        newExpiresAt: LocalDateTime,
    ): Int
}
