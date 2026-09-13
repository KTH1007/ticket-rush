package com.ticketrush.reservation.application

import com.ticketrush.event.domain.EventRepositoryPort
import com.ticketrush.reservation.domain.GradeRepositoryPort
import com.ticketrush.reservation.domain.ReservationRepositoryPort
import com.ticketrush.reservation.domain.ReservationStatus
import com.ticketrush.reservation.domain.SeatRepositoryPort
import com.ticketrush.reservation.domain.SeatStatus
import com.ticketrush.shared.PhoneHash
import com.ticketrush.support.IntegrationTest
import com.ticketrush.support.공연_하나_저장
import com.ticketrush.support.등급_하나_저장
import com.ticketrush.support.예약_하나_저장
import com.ticketrush.support.홀드된_좌석_하나_저장
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDateTime
import kotlin.test.Test

class HoldExpirySchedulerTest : IntegrationTest() {
    @Autowired
    lateinit var eventRepository: EventRepositoryPort

    @Autowired
    lateinit var gradeRepository: GradeRepositoryPort

    @Autowired
    lateinit var seatRepository: SeatRepositoryPort

    @Autowired
    lateinit var reservationRepository: ReservationRepositoryPort

    @Autowired
    lateinit var holdExpiryScheduler: HoldExpiryScheduler

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `만료된 홀드를 스윕하면 좌석과 예약이 함께 원래 상태로 되돌아간다`() {
        // given
        val event = eventRepository.공연_하나_저장()
        val grade = gradeRepository.등급_하나_저장(event)
        val phoneHash = PhoneHash(ByteArray(32) { 1 })
        val reservation =
            reservationRepository.예약_하나_저장(event, phoneHash = phoneHash, holdExpiresAt = EXPIRED_HOLD_EXPIRES_AT)
        val seat =
            seatRepository.홀드된_좌석_하나_저장(
                event,
                grade,
                reservationId = reservation.id,
                phoneHash = phoneHash,
                slotNo = 1,
                holdExpiresAt = EXPIRED_HOLD_EXPIRES_AT,
            )

        // when
        holdExpiryScheduler.sweep()

        // then
        val revertedSeat = seatRepository.findAllByEventId(event.id).single { it.id == seat.id }
        assertThat(revertedSeat.status).isEqualTo(SeatStatus.AVAILABLE)
        assertThat(상태_조회(reservation.id)).isEqualTo(ReservationStatus.EXPIRED)
    }

    @Test
    fun `같은 스윕을 두 번 실행해도 두 번째는 아무것도 바뀌지 않는다`() {
        // given
        val event = eventRepository.공연_하나_저장()
        val grade = gradeRepository.등급_하나_저장(event)
        val phoneHash = PhoneHash(ByteArray(32) { 1 })
        val reservation =
            reservationRepository.예약_하나_저장(event, phoneHash = phoneHash, holdExpiresAt = EXPIRED_HOLD_EXPIRES_AT)
        seatRepository.홀드된_좌석_하나_저장(
            event,
            grade,
            reservationId = reservation.id,
            phoneHash = phoneHash,
            slotNo = 1,
            holdExpiresAt = EXPIRED_HOLD_EXPIRES_AT,
        )
        holdExpiryScheduler.sweep()

        // when & then: 두 번째 호출이 예외 없이 끝나고 상태가 그대로 유지되면 충분하다
        holdExpiryScheduler.sweep()
        assertThat(상태_조회(reservation.id)).isEqualTo(ReservationStatus.EXPIRED)
    }

    private fun 상태_조회(id: Long): ReservationStatus =
        ReservationStatus.valueOf(
            jdbcTemplate.queryForObject("SELECT status FROM reservation WHERE id = ?", String::class.java, id)!!,
        )

    companion object {
        private val EXPIRED_HOLD_EXPIRES_AT: LocalDateTime = LocalDateTime.of(2020, 1, 1, 0, 0)
    }
}
