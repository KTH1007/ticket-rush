package com.ticketrush.reservation.application

import com.ticketrush.reservation.domain.Reservation
import com.ticketrush.reservation.domain.ReservationLimitExceededException
import com.ticketrush.reservation.domain.ReservationRepositoryPort
import com.ticketrush.reservation.domain.SeatAlreadyHeldException
import com.ticketrush.reservation.domain.SeatRepositoryPort
import com.ticketrush.reservation.domain.SeatSelection
import com.ticketrush.shared.PhoneHash
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

@Service
class ReservationCommandService(
    private val seatRepository: SeatRepositoryPort,
    private val reservationRepository: ReservationRepositoryPort,
) {
    // amount/holdExpiresAt은 이미 계산된 값을 받는다. 이 서비스의 책임은 slot 배정과
    // all-or-nothing 판단이지, 금액 계산이나 만료 시각 계산이 아니다.
    @Transactional
    fun holdSeats(
        eventId: Long,
        seatSelection: SeatSelection,
        phoneHash: PhoneHash,
        amount: Int,
        holdExpiresAt: LocalDateTime,
    ): Reservation {
        val slots = availableSlots(eventId, phoneHash, seatSelection.seatIds.size)
        val reservation = saveHoldingReservation(eventId, seatSelection, phoneHash, amount, holdExpiresAt)
        holdEachSeat(eventId, seatSelection, slots, reservation, phoneHash, holdExpiresAt)
        return reservation
    }

    private fun availableSlots(
        eventId: Long,
        phoneHash: PhoneHash,
        requiredCount: Int,
    ): List<Short> {
        val usedSlots = seatRepository.findHeldOrSoldSlotNos(eventId, phoneHash).toSet()
        val available = ALL_SLOTS.filterNot { it in usedSlots }
        if (available.size < requiredCount) throw ReservationLimitExceededException()
        return available
    }

    private fun saveHoldingReservation(
        eventId: Long,
        seatSelection: SeatSelection,
        phoneHash: PhoneHash,
        amount: Int,
        holdExpiresAt: LocalDateTime,
    ): Reservation =
        reservationRepository.save(
            Reservation(
                eventId = eventId,
                phoneHash = phoneHash,
                quantity = seatSelection.seatIds.size.toShort(),
                amount = amount,
                holdToken = UUID.randomUUID(),
                idempotencyKey = UUID.randomUUID(),
                holdExpiresAt = holdExpiresAt,
            ),
        )

    private fun holdEachSeat(
        eventId: Long,
        seatSelection: SeatSelection,
        slots: List<Short>,
        reservation: Reservation,
        phoneHash: PhoneHash,
        holdExpiresAt: LocalDateTime,
    ) {
        seatSelection.seatIds.zip(slots).forEach { (seatId, slotNo) ->
            val held =
                seatRepository.holdIfAvailable(
                    eventId = eventId,
                    seatId = seatId,
                    reservationId = reservation.id,
                    phoneHash = phoneHash,
                    slotNo = slotNo,
                    holdExpiresAt = holdExpiresAt,
                )
            if (!held) throw SeatAlreadyHeldException()
        }
    }

    companion object {
        private val ALL_SLOTS: List<Short> = listOf(1, 2)
    }
}
