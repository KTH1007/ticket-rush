package com.ticketrush.reservation.application

import com.ticketrush.reservation.SeatPolicyProperties
import com.ticketrush.reservation.domain.GradeRepositoryPort
import com.ticketrush.reservation.domain.Reservation
import com.ticketrush.reservation.domain.ReservationLimitExceededException
import com.ticketrush.reservation.domain.ReservationRepositoryPort
import com.ticketrush.reservation.domain.SeatAlreadyHeldException
import com.ticketrush.reservation.domain.SeatHoldFilterPort
import com.ticketrush.reservation.domain.SeatRepositoryPort
import com.ticketrush.reservation.domain.SeatSelection
import com.ticketrush.shared.PhoneHash
import com.ticketrush.shared.exception.ConflictException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime
import java.util.UUID

@Service
class ReservationCommandService(
    private val seatRepository: SeatRepositoryPort,
    private val reservationRepository: ReservationRepositoryPort,
    private val gradeRepository: GradeRepositoryPort,
    private val seatHoldFilter: SeatHoldFilterPort,
    private val clock: Clock,
    private val seatPolicy: SeatPolicyProperties,
) {
    @Transactional
    fun holdSeats(
        eventId: Long,
        seatSelection: SeatSelection,
        phoneHash: PhoneHash,
    ): Reservation {
        val holdToken = UUID.randomUUID()
        if (!seatHoldFilter.tryClaim(eventId, seatSelection.seatIds, holdToken.toString())) {
            throw SeatAlreadyHeldException()
        }
        return try {
            holdSeatsInDb(eventId, seatSelection, phoneHash, holdToken)
        } catch (e: ConflictException) {
            seatHoldFilter.release(eventId, seatSelection.seatIds, holdToken.toString())
            throw e
        }
    }

    private fun holdSeatsInDb(
        eventId: Long,
        seatSelection: SeatSelection,
        phoneHash: PhoneHash,
        holdToken: UUID,
    ): Reservation {
        val slots = availableSlots(eventId, phoneHash, seatSelection.seatIds.size)
        val amount = computeAmount(eventId, seatSelection.seatIds)
        val holdExpiresAt = LocalDateTime.now(clock).plus(seatPolicy.holdTtl)
        val reservation = saveHoldingReservation(eventId, seatSelection, phoneHash, amount, holdExpiresAt, holdToken)
        holdEachSeat(eventId, seatSelection, slots, reservation, phoneHash, holdExpiresAt)
        return reservation
    }

    private fun computeAmount(
        eventId: Long,
        seatIds: List<Long>,
    ): Int {
        val priceByGradeId = gradeRepository.findAllByEventId(eventId).associate { it.id to it.price }
        return seatRepository.findAllByIds(seatIds).sumOf { priceByGradeId.getValue(it.gradeId) }
    }

    private fun availableSlots(
        eventId: Long,
        phoneHash: PhoneHash,
        requiredCount: Int,
    ): List<Short> {
        val usedSlots = seatRepository.findHeldOrSoldSlotNos(eventId, phoneHash).toSet()
        val allSlots = (1..seatPolicy.maxPerPhone).map { it.toShort() }
        val available = allSlots.filterNot { it in usedSlots }
        if (available.size < requiredCount) throw ReservationLimitExceededException()
        return available
    }

    private fun saveHoldingReservation(
        eventId: Long,
        seatSelection: SeatSelection,
        phoneHash: PhoneHash,
        amount: Int,
        holdExpiresAt: LocalDateTime,
        holdToken: UUID,
    ): Reservation =
        reservationRepository.save(
            Reservation(
                eventId = eventId,
                phoneHash = phoneHash,
                quantity = seatSelection.seatIds.size.toShort(),
                amount = amount,
                holdToken = holdToken,
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
}
