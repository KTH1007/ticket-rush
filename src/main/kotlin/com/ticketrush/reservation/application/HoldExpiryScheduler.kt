package com.ticketrush.reservation.application

import com.ticketrush.reservation.domain.ReservationRepositoryPort
import com.ticketrush.reservation.domain.SeatRepositoryPort
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

// 홀드 만료 스위퍼. Redis 홀드 키는 TTL로 알아서 사라지지만 DB 상태는 그대로 남는다.
// hold_expires_at이 지난 HELD 좌석/HOLDING 예약을 주기적으로 원래 상태로 되돌린다.
// 두 UPDATE 모두 상태를 조건으로 걸어 자기 자신에 대해 멱등이라, 여러 인스턴스가
// 동시에 돌거나 같은 인스턴스가 반복 실행해도 중복 처리되지 않는다.
@Component
class HoldExpiryScheduler(
    private val seatRepository: SeatRepositoryPort,
    private val reservationRepository: ReservationRepositoryPort,
    private val clock: Clock,
) {
    @Scheduled(fixedRate = SWEEP_INTERVAL_MS)
    @Transactional
    fun sweep() {
        val now = LocalDateTime.now(clock)
        reservationRepository.expireHoldingReservations(now)
        seatRepository.releaseExpiredHolds(now)
    }

    companion object {
        private const val SWEEP_INTERVAL_MS = 30_000L
    }
}
