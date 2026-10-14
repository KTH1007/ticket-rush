package com.ticketrush.payment.application

import com.ticketrush.reservation.domain.Reservation
import com.ticketrush.reservation.domain.SeatHoldShortenedEvent
import com.ticketrush.reservation.domain.SeatRepositoryPort
import com.ticketrush.reservation.domain.SeatsReturnedEvent
import com.ticketrush.reservation.domain.SeatsSoldEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.time.LocalDateTime

// 좌석 상태 변경과 마크 동기화 이벤트를 한 곳에 묶어 호출 지점이 이벤트를 빠뜨리지 못하게 한다
@Component
class PaymentSeatUpdater(
    private val seatRepository: SeatRepositoryPort,
    private val eventPublisher: ApplicationEventPublisher,
) {
    fun markSold(reservation: Reservation) {
        seatRepository.markSold(reservation.id)
        eventPublisher.publishEvent(SeatsSoldEvent(reservation.eventId, seatIdsOf(reservation), reservation.holdToken))
    }

    fun shortenHold(
        reservation: Reservation,
        newExpiresAt: LocalDateTime,
    ) {
        seatRepository.shortenHoldExpiry(reservation.id, newExpiresAt)
        eventPublisher.publishEvent(SeatHoldShortenedEvent(reservation.eventId, seatIdsOf(reservation), reservation.holdToken))
    }

    // 되돌린 좌석은 예약 id를 잃으므로 되돌리기 전에 좌석 id를 뽑는다
    fun returnToAvailable(reservation: Reservation) {
        val seatIds = seatIdsOf(reservation)
        seatRepository.returnToAvailable(reservation.id)
        eventPublisher.publishEvent(SeatsReturnedEvent(reservation.eventId, seatIds, reservation.holdToken))
    }

    private fun seatIdsOf(reservation: Reservation): List<Long> = seatRepository.findAllByReservationId(reservation.id).map { it.id }
}
