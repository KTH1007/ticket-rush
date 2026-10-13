package com.ticketrush.reservation.infrastructure

import com.ticketrush.event.domain.EventRepositoryPort
import com.ticketrush.reservation.domain.GradeRepositoryPort
import com.ticketrush.reservation.domain.ReservationRepositoryPort
import com.ticketrush.reservation.domain.SeatRepositoryPort
import com.ticketrush.reservation.domain.SeatStatus
import com.ticketrush.shared.PhoneHash
import com.ticketrush.support.IntegrationTest
import com.ticketrush.support.공연_하나_저장
import com.ticketrush.support.등급_하나_저장
import com.ticketrush.support.예약_하나_저장
import com.ticketrush.support.좌석_하나_저장
import com.ticketrush.support.홀드된_좌석_하나_저장
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.Test

class SeatFullySoldTest : IntegrationTest() {
    @Autowired
    lateinit var eventRepository: EventRepositoryPort

    @Autowired
    lateinit var gradeRepository: GradeRepositoryPort

    @Autowired
    lateinit var seatRepository: SeatRepositoryPort

    @Autowired
    lateinit var reservationRepository: ReservationRepositoryPort

    private val phoneHash = PhoneHash(ByteArray(32) { 3 })

    @Test
    fun `좌석이 하나도 없는 공연은 매진이 아니다`() {
        val event = eventRepository.공연_하나_저장()

        assertThat(seatRepository.isFullySold(event.id)).isFalse()
    }

    @Test
    fun `팔리지 않은 좌석이 하나라도 있으면 매진이 아니다`() {
        val event = eventRepository.공연_하나_저장()
        val grade = gradeRepository.등급_하나_저장(event)
        seatRepository.좌석_하나_저장(event, grade, seatNo = 1, ordinal = 1)
        val reservation = reservationRepository.예약_하나_저장(event, phoneHash = phoneHash)
        seatRepository.홀드된_좌석_하나_저장(event, grade, reservation.id, phoneHash, slotNo = 1, seatNo = 2, status = SeatStatus.SOLD)

        assertThat(seatRepository.isFullySold(event.id)).isFalse()
    }

    @Test
    fun `홀드 중인 좌석이 남아 있으면 매진이 아니다`() {
        val event = eventRepository.공연_하나_저장()
        val grade = gradeRepository.등급_하나_저장(event)
        val reservation = reservationRepository.예약_하나_저장(event, phoneHash = phoneHash)
        seatRepository.홀드된_좌석_하나_저장(event, grade, reservation.id, phoneHash, slotNo = 1, seatNo = 1, status = SeatStatus.SOLD)
        seatRepository.홀드된_좌석_하나_저장(event, grade, reservation.id, phoneHash, slotNo = 2, seatNo = 2, status = SeatStatus.HELD)

        assertThat(seatRepository.isFullySold(event.id)).isFalse()
    }

    @Test
    fun `좌석이 전부 SOLD이면 매진이다`() {
        val event = eventRepository.공연_하나_저장()
        val grade = gradeRepository.등급_하나_저장(event)
        val reservation = reservationRepository.예약_하나_저장(event, phoneHash = phoneHash)
        seatRepository.홀드된_좌석_하나_저장(event, grade, reservation.id, phoneHash, slotNo = 1, seatNo = 1, status = SeatStatus.SOLD)
        seatRepository.홀드된_좌석_하나_저장(event, grade, reservation.id, phoneHash, slotNo = 2, seatNo = 2, status = SeatStatus.SOLD)

        assertThat(seatRepository.isFullySold(event.id)).isTrue()
    }
}
