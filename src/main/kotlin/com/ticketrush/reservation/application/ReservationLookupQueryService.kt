package com.ticketrush.reservation.application

import com.ticketrush.reservation.domain.GradeQueryPort
import com.ticketrush.reservation.domain.Reservation
import com.ticketrush.reservation.domain.ReservationLookupFailedException
import com.ticketrush.reservation.domain.ReservationLookupRateLimitedException
import com.ticketrush.reservation.domain.ReservationLookupRateLimiterPort
import com.ticketrush.reservation.domain.ReservationLookupResult
import com.ticketrush.reservation.domain.ReservationLookupSeat
import com.ticketrush.reservation.domain.ReservationRepositoryPort
import com.ticketrush.reservation.domain.SeatQueryPort
import com.ticketrush.shared.PhoneHasher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ReservationLookupQueryService(
    private val reservationRepository: ReservationRepositoryPort,
    private val seatRepository: SeatQueryPort,
    private val gradeRepository: GradeQueryPort,
    private val rateLimiter: ReservationLookupRateLimiterPort,
    private val phoneHasher: PhoneHasher,
) {
    @Transactional(readOnly = true)
    fun lookup(
        reservationNo: String,
        phone: String,
    ): ReservationLookupResult {
        if (!rateLimiter.tryReserveAttempt(reservationNo)) throw ReservationLookupRateLimitedException()

        val reservation = reservationRepository.findByReservationNo(reservationNo)
        if (reservation == null || reservation.phoneHash != phoneHasher.hash(phone)) {
            throw ReservationLookupFailedException()
        }

        rateLimiter.releaseAttempt(reservationNo)
        return ReservationLookupResult(reservation, seatsOf(reservation))
    }

    private fun seatsOf(reservation: Reservation): List<ReservationLookupSeat> {
        val seats = seatRepository.findAllByReservationId(reservation.id)
        val gradeNames = gradeRepository.findAllByIds(seats.map { it.gradeId }.distinct()).associateBy { it.id }
        return seats.map { seat ->
            val gradeName = requireNotNull(gradeNames[seat.gradeId]) { "좌석의 등급을 찾을 수 없습니다: ${seat.gradeId}" }.name
            ReservationLookupSeat(section = seat.section, rowLabel = seat.rowLabel, seatNo = seat.seatNo, gradeName = gradeName)
        }
    }
}
