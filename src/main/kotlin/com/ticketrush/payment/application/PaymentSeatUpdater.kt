package com.ticketrush.payment.application

import com.ticketrush.reservation.domain.Reservation
import com.ticketrush.reservation.domain.SeatHoldShortenedEvent
import com.ticketrush.reservation.domain.SeatRepositoryPort
import com.ticketrush.reservation.domain.SeatsReturnedEvent
import com.ticketrush.reservation.domain.SeatsSoldEvent
import org.springframework.stereotype.Component
import java.time.LocalDateTime

// 좌석 상태를 바꾸고, 커밋 뒤 마크 동기화에 쓸 값을 돌려준다. 호출한 쪽이 트랜잭션이 끝난 뒤에 그 값을 동기화 포트로 넘긴다
@Component
class PaymentSeatUpdater(
    private val seatRepository: SeatRepositoryPort,
) {
    fun markSold(reservation: Reservation): SeatsSoldEvent {
        seatRepository.markSold(reservation.id)
        return SeatsSoldEvent(reservation.eventId, seatIdsOf(reservation), reservation.holdToken)
    }

    fun shortenHold(
        reservation: Reservation,
        newExpiresAt: LocalDateTime,
    ): SeatHoldShortenedEvent {
        seatRepository.shortenHoldExpiry(reservation.id, newExpiresAt)
        return SeatHoldShortenedEvent(reservation.eventId, seatIdsOf(reservation), reservation.holdToken)
    }

    // 되돌린 좌석은 예약 id를 잃으므로 되돌리기 전에 좌석 id를 뽑는다
    fun returnToAvailable(reservation: Reservation): SeatsReturnedEvent {
        val seatIds = seatIdsOf(reservation)
        seatRepository.returnToAvailable(reservation.id)
        return SeatsReturnedEvent(reservation.eventId, seatIds, reservation.holdToken)
    }

    private fun seatIdsOf(reservation: Reservation): List<Long> = seatRepository.findAllByReservationId(reservation.id).map { it.id }
}
