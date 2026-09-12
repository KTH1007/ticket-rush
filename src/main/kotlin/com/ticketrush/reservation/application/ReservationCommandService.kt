package com.ticketrush.reservation.application

import com.ticketrush.reservation.SeatPolicyProperties
import com.ticketrush.reservation.domain.GradeRepositoryPort
import com.ticketrush.reservation.domain.Reservation
import com.ticketrush.reservation.domain.ReservationLimitExceededException
import com.ticketrush.reservation.domain.ReservationRepositoryPort
import com.ticketrush.reservation.domain.SeatAlreadyHeldException
import com.ticketrush.reservation.domain.SeatHoldFilterPort
import com.ticketrush.reservation.domain.SeatNotFoundException
import com.ticketrush.reservation.domain.SeatRepositoryPort
import com.ticketrush.reservation.domain.SeatSelection
import com.ticketrush.shared.PhoneHash
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.LocalDateTime
import java.util.UUID

private val logger = KotlinLogging.logger {}

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
        releaseOnRollback(eventId, seatSelection.seatIds, holdToken)
        return holdSeatsInDb(eventId, seatSelection, phoneHash, holdToken)
    }

    // @Transactional은 AOP 프록시라 실제 커밋은 이 메서드가 리턴한 "다음"에 일어난다. 그래서
    // try/finally로는 메서드 본문 실패만 잡을 수 있고, 커밋 자체의 실패(seat.reservation_id가
    // DEFERRABLE INITIALLY DEFERRED FK라 참조 무결성 검증이 커밋 시점으로 미뤄짐)는 못 잡는다.
    // afterCompletion은 커밋/롤백이 실제로 끝난 뒤 호출되므로 두 실패 경로를 전부 덮는다.
    private fun releaseOnRollback(
        eventId: Long,
        seatIds: List<Long>,
        holdToken: UUID,
    ) {
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCompletion(status: Int) {
                    if (status != TransactionSynchronization.STATUS_COMMITTED) {
                        releaseQuietly(eventId, seatIds, holdToken)
                    }
                }
            },
        )
    }

    // 실패 종류를 가리지 않고 전부 여기로 온다. release 자체가 실패해도 원래 예외를 삼키지 않도록 별도로 감싸고, 그 실패는 로그로만 남긴다.
    @Suppress("TooGenericExceptionCaught")
    private fun releaseQuietly(
        eventId: Long,
        seatIds: List<Long>,
        holdToken: UUID,
    ) {
        try {
            seatHoldFilter.release(eventId, seatIds, holdToken.toString())
        } catch (e: Exception) {
            logger.error(e) {
                "홀드 실패 후 Redis 클레임 해제 실패. eventId=$eventId, seatIds=$seatIds, holdToken=$holdToken " +
                    "- TTL(${seatPolicy.holdTtl}) 만료 전까지 좌석이 잘못 점유 표시될 수 있음"
            }
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

    // 존재하지 않거나 다른 공연 소속인 좌석 id가 섞여 있으면 조용히 넘어가지 않고 즉시 실패시킨다.
    private fun computeAmount(
        eventId: Long,
        seatIds: List<Long>,
    ): Int {
        val priceByGradeId = gradeRepository.findAllByEventId(eventId).associate { it.id to it.price }
        val seatById = seatRepository.findAllByIds(seatIds).associateBy { it.id }
        return seatIds.sumOf { seatId ->
            val seat = seatById[seatId]?.takeIf { it.eventId == eventId } ?: throw SeatNotFoundException(seatId)
            priceByGradeId.getValue(seat.gradeId)
        }
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
            val held = holdSeatOrTranslateConflict(eventId, seatId, reservation, phoneHash, slotNo, holdExpiresAt)
            if (!held) throw SeatAlreadyHeldException()
        }
    }

    private fun holdSeatOrTranslateConflict(
        eventId: Long,
        seatId: Long,
        reservation: Reservation,
        phoneHash: PhoneHash,
        slotNo: Short,
        holdExpiresAt: LocalDateTime,
    ): Boolean =
        try {
            seatRepository.holdIfAvailable(
                eventId = eventId,
                seatId = seatId,
                reservationId = reservation.id,
                phoneHash = phoneHash,
                slotNo = slotNo,
                holdExpiresAt = holdExpiresAt,
            )
        } catch (e: DataIntegrityViolationException) {
            if (e.message?.contains("ux_seat_slot") == true) throw ReservationLimitExceededException()
            throw e
        }
}
