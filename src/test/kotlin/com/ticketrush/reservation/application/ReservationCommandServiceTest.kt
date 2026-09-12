package com.ticketrush.reservation.application

import com.ticketrush.event.domain.Event
import com.ticketrush.event.domain.EventRepositoryPort
import com.ticketrush.reservation.domain.Grade
import com.ticketrush.reservation.domain.GradeRepositoryPort
import com.ticketrush.reservation.domain.ReservationLimitExceededException
import com.ticketrush.reservation.domain.ReservationRepositoryPort
import com.ticketrush.reservation.domain.Seat
import com.ticketrush.reservation.domain.SeatAlreadyHeldException
import com.ticketrush.reservation.domain.SeatNotFoundException
import com.ticketrush.reservation.domain.SeatRepositoryPort
import com.ticketrush.reservation.domain.SeatSelection
import com.ticketrush.reservation.domain.SeatStatus
import com.ticketrush.shared.PhoneHash
import com.ticketrush.support.IntegrationTest
import com.ticketrush.support.공연_하나_저장
import com.ticketrush.support.등급_하나_저장
import com.ticketrush.support.예약_하나_저장
import com.ticketrush.support.홀드된_좌석_하나_저장
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.Test

class ReservationCommandServiceTest : IntegrationTest() {
    @Autowired
    lateinit var eventRepository: EventRepositoryPort

    @Autowired
    lateinit var gradeRepository: GradeRepositoryPort

    @Autowired
    lateinit var seatRepository: SeatRepositoryPort

    @Autowired
    lateinit var reservationRepository: ReservationRepositoryPort

    @Autowired
    lateinit var reservationCommandService: ReservationCommandService

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `좌석 1석을 홀드하면 slot 1이 배정된 예약이 생성된다`() {
        // given
        val event = eventRepository.공연_하나_저장()
        val grade = gradeRepository.등급_하나_저장(event)
        val seat = seatRepository.save(좌석(event, grade, seatNo = 1))
        val phoneHash = PhoneHash(ByteArray(32) { 1 })

        // when
        val reservation = 홀드(event.id, listOf(seat.id), phoneHash)

        // then
        assertThat(reservation.quantity).isEqualTo(1)
        val held = 좌석_다시_조회(event.id, seat.id)
        assertThat(held.status).isEqualTo(SeatStatus.HELD)
        assertThat(held.slotNo).isEqualTo(1.toShort())
        assertThat(held.reservationId).isEqualTo(reservation.id)
    }

    @Test
    fun `좌석 2석을 홀드하면 slot 1, 2가 각각 배정된다`() {
        // given
        val event = eventRepository.공연_하나_저장()
        val grade = gradeRepository.등급_하나_저장(event)
        val seat1 = seatRepository.save(좌석(event, grade, seatNo = 1))
        val seat2 = seatRepository.save(좌석(event, grade, seatNo = 2))
        val phoneHash = PhoneHash(ByteArray(32) { 1 })

        // when
        val reservation = 홀드(event.id, listOf(seat1.id, seat2.id), phoneHash)

        // then
        assertThat(reservation.quantity).isEqualTo(2)
        val slotNos = listOf(좌석_다시_조회(event.id, seat1.id), 좌석_다시_조회(event.id, seat2.id)).map { it.slotNo }
        assertThat(slotNos).containsExactlyInAnyOrder(1.toShort(), 2.toShort())
    }

    @Test
    fun `이미 선점된 좌석이 섞여 있으면 실패하고 전부 롤백된다`() {
        // given
        val event = eventRepository.공연_하나_저장()
        val grade = gradeRepository.등급_하나_저장(event)
        val available = seatRepository.save(좌석(event, grade, seatNo = 1))
        val alreadyHeld = 타인이_홀드한_좌석(event, grade, seatNo = 2)
        val phoneHash = PhoneHash(ByteArray(32) { 1 })

        // when & then
        assertThatThrownBy { 홀드(event.id, listOf(available.id, alreadyHeld.id), phoneHash) }
            .isInstanceOf(SeatAlreadyHeldException::class.java)

        assertThat(좌석_다시_조회(event.id, available.id).status).isEqualTo(SeatStatus.AVAILABLE)
        assertThat(예약_건수(event.id, phoneHash)).isEqualTo(0L)
    }

    @Test
    fun `이미 2매를 보유한 전화번호는 예약 한도 초과로 실패한다`() {
        // given
        val event = eventRepository.공연_하나_저장()
        val grade = gradeRepository.등급_하나_저장(event)
        val phoneHash = PhoneHash(ByteArray(32) { 1 })
        일인분_홀드_완료(event, grade, phoneHash, slotNo = 1)
        일인분_홀드_완료(event, grade, phoneHash, slotNo = 2)
        val newSeat = seatRepository.save(좌석(event, grade, seatNo = 3))

        // when & then
        assertThatThrownBy { 홀드(event.id, listOf(newSeat.id), phoneHash) }
            .isInstanceOf(ReservationLimitExceededException::class.java)

        assertThat(좌석_다시_조회(event.id, newSeat.id).status).isEqualTo(SeatStatus.AVAILABLE)
    }

    @Test
    fun `slot이 1개만 남았는데 2석을 요청하면 예약 한도 초과로 실패한다`() {
        // given
        val event = eventRepository.공연_하나_저장()
        val grade = gradeRepository.등급_하나_저장(event)
        val phoneHash = PhoneHash(ByteArray(32) { 1 })
        일인분_홀드_완료(event, grade, phoneHash, slotNo = 1)
        val seat2 = seatRepository.save(좌석(event, grade, seatNo = 2))
        val seat3 = seatRepository.save(좌석(event, grade, seatNo = 3))

        // when & then
        assertThatThrownBy { 홀드(event.id, listOf(seat2.id, seat3.id), phoneHash) }
            .isInstanceOf(ReservationLimitExceededException::class.java)

        assertThat(좌석_다시_조회(event.id, seat2.id).status).isEqualTo(SeatStatus.AVAILABLE)
        assertThat(좌석_다시_조회(event.id, seat3.id).status).isEqualTo(SeatStatus.AVAILABLE)
    }

    @Test
    fun `등급 가격을 합산해서 예약 금액을 계산한다`() {
        // given
        val event = eventRepository.공연_하나_저장()
        val grade = gradeRepository.등급_하나_저장(event, price = 150_000)
        val seat1 = seatRepository.save(좌석(event, grade, seatNo = 1))
        val seat2 = seatRepository.save(좌석(event, grade, seatNo = 2))
        val phoneHash = PhoneHash(ByteArray(32) { 1 })

        // when
        val reservation = 홀드(event.id, listOf(seat1.id, seat2.id), phoneHash)

        // then
        assertThat(reservation.amount).isEqualTo(300_000)
    }

    @Test
    fun `다른 공연 소속 좌석 id가 섞이면 좌석을 찾을 수 없다는 예외가 발생하고 아무것도 바뀌지 않는다`() {
        // given
        val event = eventRepository.공연_하나_저장()
        val grade = gradeRepository.등급_하나_저장(event)
        val seat = seatRepository.save(좌석(event, grade, seatNo = 1))
        val otherEvent = eventRepository.공연_하나_저장(title = "다른 공연")
        val otherGrade = gradeRepository.등급_하나_저장(otherEvent)
        val seatInOtherEvent = seatRepository.save(좌석(otherEvent, otherGrade, seatNo = 1))
        val phoneHash = PhoneHash(ByteArray(32) { 1 })

        // when & then
        assertThatThrownBy { 홀드(event.id, listOf(seat.id, seatInOtherEvent.id), phoneHash) }
            .isInstanceOf(SeatNotFoundException::class.java)

        assertThat(좌석_다시_조회(event.id, seat.id).status).isEqualTo(SeatStatus.AVAILABLE)
    }

    private fun 홀드(
        eventId: Long,
        seatIds: List<Long>,
        phoneHash: PhoneHash,
    ) = reservationCommandService.holdSeats(
        eventId = eventId,
        seatSelection = SeatSelection(seatIds),
        phoneHash = phoneHash,
    )

    private fun 좌석(
        event: Event,
        grade: Grade,
        seatNo: Short,
    ): Seat = Seat(eventId = event.id, gradeId = grade.id, section = "A", rowLabel = "1", seatNo = seatNo, ordinal = seatNo.toInt())

    private fun 좌석_다시_조회(
        eventId: Long,
        seatId: Long,
    ): Seat = seatRepository.findAllByEventId(eventId).single { it.id == seatId }

    // 다른 전화번호로 이미 홀드해둔 좌석. "이미 선점된 좌석" 시나리오 셋업용.
    private fun 타인이_홀드한_좌석(
        event: Event,
        grade: Grade,
        seatNo: Short,
    ): Seat {
        val otherPhoneHash = PhoneHash(ByteArray(32) { 9 })
        val otherReservation = reservationRepository.예약_하나_저장(event, phoneHash = otherPhoneHash)
        return seatRepository.홀드된_좌석_하나_저장(
            event,
            grade,
            reservationId = otherReservation.id,
            phoneHash = otherPhoneHash,
            slotNo = 1,
            seatNo = seatNo,
        )
    }

    // 특정 slot 하나를 이미 채운 상태를 만든다. 1인 2매 제한 테스트에서 두 번 부르면
    // "이미 2매 보유", 한 번만 부르면 "슬롯 1개만 남음" 시나리오가 된다.
    private fun 일인분_홀드_완료(
        event: Event,
        grade: Grade,
        phoneHash: PhoneHash,
        slotNo: Short,
    ) {
        val reservation = reservationRepository.예약_하나_저장(event, phoneHash = phoneHash)
        seatRepository.홀드된_좌석_하나_저장(
            event,
            grade,
            reservationId = reservation.id,
            phoneHash = phoneHash,
            slotNo = slotNo,
            seatNo = (90 + slotNo).toShort(),
        )
    }

    private fun 예약_건수(
        eventId: Long,
        phoneHash: PhoneHash,
    ): Long =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM reservation WHERE event_id = ? AND phone_hash = ?",
            Long::class.java,
            eventId,
            phoneHash.value,
        ) ?: 0L
}
