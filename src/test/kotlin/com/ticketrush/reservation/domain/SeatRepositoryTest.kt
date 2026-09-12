package com.ticketrush.reservation.domain

import com.ticketrush.event.domain.Event
import com.ticketrush.event.domain.EventRepositoryPort
import com.ticketrush.shared.PhoneHash
import com.ticketrush.support.IntegrationTest
import com.ticketrush.support.공연_하나_저장
import com.ticketrush.support.등급_하나_저장
import com.ticketrush.support.예약_하나_저장
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.tuple
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDateTime
import kotlin.test.Test

class SeatRepositoryTest : IntegrationTest() {
    @Autowired
    lateinit var eventRepository: EventRepositoryPort

    @Autowired
    lateinit var gradeRepository: GradeRepositoryPort

    @Autowired
    lateinit var seatRepository: SeatRepositoryPort

    @Autowired
    lateinit var reservationRepository: ReservationRepositoryPort

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `Seat가 Event, Grade를 참조하며 저장됨`() {
        // given
        val event = eventRepository.공연_하나_저장()
        val grade = gradeRepository.등급_하나_저장(event)

        // when
        val saved =
            seatRepository.save(
                Seat(
                    eventId = event.id,
                    gradeId = grade.id,
                    section = "A",
                    rowLabel = "1",
                    seatNo = 1,
                    ordinal = 0,
                ),
            )

        // then
        assertThat(saved.eventId).isEqualTo(event.id)
        assertThat(saved.gradeId).isEqualTo(grade.id)
        assertThat(saved.status).isEqualTo(SeatStatus.AVAILABLE)
    }

    @Test
    fun `uk_seat_position 위반 시 예외 발생`() {
        // given
        val event = eventRepository.공연_하나_저장()
        val grade = gradeRepository.등급_하나_저장(event)
        seatRepository.save(
            Seat(eventId = event.id, gradeId = grade.id, section = "A", rowLabel = "1", seatNo = 1, ordinal = 0),
        )

        // when & then
        assertThatThrownBy {
            seatRepository.save(
                Seat(eventId = event.id, gradeId = grade.id, section = "A", rowLabel = "1", seatNo = 1, ordinal = 1),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `uk_seat_ordinal 위반 시 예외 발생`() {
        // given
        val event = eventRepository.공연_하나_저장()
        val grade = gradeRepository.등급_하나_저장(event)
        seatRepository.save(
            Seat(eventId = event.id, gradeId = grade.id, section = "A", rowLabel = "1", seatNo = 1, ordinal = 0),
        )

        // when & then
        assertThatThrownBy {
            seatRepository.save(
                Seat(eventId = event.id, gradeId = grade.id, section = "A", rowLabel = "2", seatNo = 1, ordinal = 0),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `ck_seat_status 위반 값 저장 시도 시 예외 발생`() {
        // given
        val event = eventRepository.공연_하나_저장()
        val grade = gradeRepository.등급_하나_저장(event)

        // when & then
        // status는 enum이라 우회 INSERT로 검증. ck_seat_status와 V5의 ck_seat_lifecycle이 항상 같이 걸려 실제 보고되는 제약을 메시지로 확인한다.
        assertThatThrownBy {
            jdbcTemplate.update(
                """
                INSERT INTO seat (event_id, grade_id, section, row_label, seat_no, ordinal, status, reservation_id, created_at, updated_at)
                VALUES (?, ?, 'A', '1', 1, 0, 'INVALID', 1, now(), now())
                """.trimIndent(),
                event.id,
                grade.id,
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
            .hasMessageContaining("ck_seat_lifecycle")
    }

    @Test
    fun `findAllByEventId로 좌석을 위치 순서로 조회`() {
        // given
        val event = eventRepository.공연_하나_저장()
        val grade = gradeRepository.등급_하나_저장(event)
        seatRepository.save(Seat(eventId = event.id, gradeId = grade.id, section = "A", rowLabel = "2", seatNo = 1, ordinal = 2))
        seatRepository.save(Seat(eventId = event.id, gradeId = grade.id, section = "A", rowLabel = "1", seatNo = 2, ordinal = 1))
        seatRepository.save(Seat(eventId = event.id, gradeId = grade.id, section = "A", rowLabel = "1", seatNo = 1, ordinal = 0))

        // when
        val seats = seatRepository.findAllByEventId(event.id)

        // then
        assertThat(seats)
            .extracting("rowLabel", "seatNo")
            .containsExactly(
                tuple("1", 1.toShort()),
                tuple("1", 2.toShort()),
                tuple("2", 1.toShort()),
            )
    }

    @Test
    fun `좌석이 없는 이벤트는 빈 리스트를 반환`() {
        // given
        val event = eventRepository.공연_하나_저장()

        // when
        val seats = seatRepository.findAllByEventId(event.id)

        // then
        assertThat(seats).isEmpty()
    }

    @Test
    fun `조회 쿼리가 인덱스를 타고 시퀀셜 스캔을 하지 않는다`() {
        // given
        val event = eventRepository.공연_하나_저장()
        val grade = gradeRepository.등급_하나_저장(event)
        val otherEvent = eventRepository.공연_하나_저장(title = "다른 공연")
        val otherGrade = gradeRepository.등급_하나_저장(otherEvent)

        // 좌석 1건짜리 테이블에선 플래너 선택이 통계/비용 상수에 따라 우연히 갈릴 수 있다.
        // 공연당 수만 건이라는 실제 규모를 흉내내야 안정적으로 검증된다. 건별 save()는
        // 수천 건에서 너무 느려 배치 INSERT로 채운다.
        좌석_대량_저장(event.id, grade.id, count = 1000, ordinalOffset = 0)
        좌석_대량_저장(otherEvent.id, otherGrade.id, count = 1000, ordinalOffset = 1000)
        jdbcTemplate.execute("ANALYZE seat")

        // when
        val plan =
            jdbcTemplate
                .queryForList(
                    "EXPLAIN SELECT * FROM seat WHERE event_id = ? ORDER BY section, row_label, seat_no",
                    event.id,
                ).joinToString("\n") { it.values.first().toString() }

        // then
        // ix_seat_event_status(event_id, status)가 아니라 uk_seat_position
        // (event_id, section, row_label, seat_no)이 잡힌다. 컬럼 순서가 이 쿼리의
        // WHERE+ORDER BY와 정확히 일치해서 별도 Sort 없이 처리되는 더 나은 플랜이다.
        assertThat(plan).contains("Index Scan")
        assertThat(plan).doesNotContain("Seq Scan")
    }

    @Test
    fun `HELD 또는 SOLD 좌석의 slot 번호를 반환한다`() {
        // given
        val event = eventRepository.공연_하나_저장()
        val grade = gradeRepository.등급_하나_저장(event)
        val phoneHash = PhoneHash(ByteArray(32) { 1 })
        이미_홀드된_좌석_저장(event, grade, seatNo = 1, slotNo = 1, phoneHash = phoneHash)
        이미_홀드된_좌석_저장(event, grade, seatNo = 2, slotNo = 2, phoneHash = phoneHash, status = SeatStatus.SOLD)

        // when
        val slotNos = seatRepository.findHeldOrSoldSlotNos(event.id, phoneHash)

        // then
        assertThat(slotNos).containsExactlyInAnyOrder(1, 2)
    }

    @Test
    fun `AVAILABLE 좌석은 포함하지 않는다`() {
        // given
        val event = eventRepository.공연_하나_저장()
        val grade = gradeRepository.등급_하나_저장(event)
        좌석_저장(event, grade, seatNo = 1)

        // when
        val slotNos = seatRepository.findHeldOrSoldSlotNos(event.id, PhoneHash(ByteArray(32) { 1 }))

        // then
        assertThat(slotNos).isEmpty()
    }

    @Test
    fun `다른 전화번호의 좌석은 포함하지 않는다`() {
        // given
        val event = eventRepository.공연_하나_저장()
        val grade = gradeRepository.등급_하나_저장(event)
        이미_홀드된_좌석_저장(event, grade, seatNo = 1, slotNo = 1, phoneHash = PhoneHash(ByteArray(32) { 9 }))

        // when
        val slotNos = seatRepository.findHeldOrSoldSlotNos(event.id, PhoneHash(ByteArray(32) { 1 }))

        // then
        assertThat(slotNos).isEmpty()
    }

    @Test
    fun `다른 이벤트의 좌석은 포함하지 않는다`() {
        // given
        val event = eventRepository.공연_하나_저장()
        val otherEvent = eventRepository.공연_하나_저장(title = "다른 공연")
        val otherGrade = gradeRepository.등급_하나_저장(otherEvent)
        val phoneHash = PhoneHash(ByteArray(32) { 1 })
        이미_홀드된_좌석_저장(otherEvent, otherGrade, seatNo = 1, slotNo = 1, phoneHash = phoneHash)

        // when
        val slotNos = seatRepository.findHeldOrSoldSlotNos(event.id, phoneHash)

        // then
        assertThat(slotNos).isEmpty()
    }

    @Test
    fun `AVAILABLE 좌석은 홀드에 성공한다`() {
        // given
        val event = eventRepository.공연_하나_저장()
        val grade = gradeRepository.등급_하나_저장(event)
        val seat = 좌석_저장(event, grade, seatNo = 1)
        val reservation = reservationRepository.예약_하나_저장(event)

        // when
        val result =
            seatRepository.holdIfAvailable(
                eventId = event.id,
                seatId = seat.id,
                reservationId = reservation.id,
                phoneHash = PhoneHash(ByteArray(32) { 1 }),
                slotNo = 1,
                holdExpiresAt = FIXED_HOLD_EXPIRES_AT,
            )

        // then
        assertThat(result).isTrue()
        val held = seatRepository.findAllByEventId(event.id).single { it.id == seat.id }
        assertThat(held.status).isEqualTo(SeatStatus.HELD)
        assertThat(held.version).isEqualTo(seat.version + 1)
    }

    @Test
    fun `이미 HELD인 좌석은 홀드에 실패한다`() {
        // given
        val event = eventRepository.공연_하나_저장()
        val grade = gradeRepository.등급_하나_저장(event)
        val seat = 이미_홀드된_좌석_저장(event, grade, seatNo = 1, slotNo = 1, phoneHash = PhoneHash(ByteArray(32) { 9 }))
        val reservation = reservationRepository.예약_하나_저장(event)

        // when
        val result =
            seatRepository.holdIfAvailable(
                eventId = event.id,
                seatId = seat.id,
                reservationId = reservation.id,
                phoneHash = PhoneHash(ByteArray(32) { 1 }),
                slotNo = 2,
                holdExpiresAt = FIXED_HOLD_EXPIRES_AT,
            )

        // then
        assertThat(result).isFalse()
    }

    @Test
    fun `다른 이벤트 소속 좌석이면 홀드에 실패한다`() {
        // given
        val event = eventRepository.공연_하나_저장()
        val otherEvent = eventRepository.공연_하나_저장(title = "다른 공연")
        val otherGrade = gradeRepository.등급_하나_저장(otherEvent)
        val seatInOtherEvent = 좌석_저장(otherEvent, otherGrade, seatNo = 1)
        val reservation = reservationRepository.예약_하나_저장(event)

        // when
        val result =
            seatRepository.holdIfAvailable(
                eventId = event.id,
                seatId = seatInOtherEvent.id,
                reservationId = reservation.id,
                phoneHash = PhoneHash(ByteArray(32) { 1 }),
                slotNo = 1,
                holdExpiresAt = FIXED_HOLD_EXPIRES_AT,
            )

        // then
        assertThat(result).isFalse()
    }

    @Test
    fun `여러 id로 좌석을 한 번에 조회한다`() {
        // given
        val event = eventRepository.공연_하나_저장()
        val grade = gradeRepository.등급_하나_저장(event)
        val seat1 = 좌석_저장(event, grade, seatNo = 1)
        좌석_저장(event, grade, seatNo = 2)
        val seat3 = 좌석_저장(event, grade, seatNo = 3)

        // when
        val seats = seatRepository.findAllByIds(listOf(seat1.id, seat3.id))

        // then
        assertThat(seats).extracting("id").containsExactlyInAnyOrder(seat1.id, seat3.id)
    }

    @Test
    fun `존재하지 않는 id는 결과에서 빠진다`() {
        // given
        val event = eventRepository.공연_하나_저장()
        val grade = gradeRepository.등급_하나_저장(event)
        val seat = 좌석_저장(event, grade, seatNo = 1)

        // when
        val seats = seatRepository.findAllByIds(listOf(seat.id, 999_999L))

        // then
        assertThat(seats).extracting("id").containsExactly(seat.id)
    }

    @Test
    fun `ux_seat_slot 위반 시(같은 전화번호가 같은 slot을 중복 사용) 예외 발생`() {
        // given
        val event = eventRepository.공연_하나_저장()
        val grade = gradeRepository.등급_하나_저장(event)
        val phoneHash = PhoneHash(ByteArray(32) { 1 })
        이미_홀드된_좌석_저장(event, grade, seatNo = 1, slotNo = 1, phoneHash = phoneHash)

        // when & then
        assertThatThrownBy {
            이미_홀드된_좌석_저장(event, grade, seatNo = 2, slotNo = 1, phoneHash = phoneHash)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
            .hasMessageContaining("ux_seat_slot")
    }

    private fun 좌석_저장(
        event: Event,
        grade: Grade,
        seatNo: Short,
    ): Seat =
        seatRepository.save(
            Seat(eventId = event.id, gradeId = grade.id, section = "A", rowLabel = "1", seatNo = seatNo, ordinal = seatNo.toInt()),
        )

    private fun 이미_홀드된_좌석_저장(
        event: Event,
        grade: Grade,
        seatNo: Short,
        slotNo: Short,
        phoneHash: PhoneHash,
        status: SeatStatus = SeatStatus.HELD,
    ): Seat =
        seatRepository.save(
            Seat(
                eventId = event.id,
                gradeId = grade.id,
                section = "A",
                rowLabel = "1",
                seatNo = seatNo,
                ordinal = seatNo.toInt(),
                status = status,
                reservationId = reservationRepository.예약_하나_저장(event).id,
                phoneHash = phoneHash,
                slotNo = slotNo,
                holdExpiresAt = FIXED_HOLD_EXPIRES_AT,
            ),
        )

    private fun 좌석_대량_저장(
        eventId: Long,
        gradeId: Long,
        count: Int,
        ordinalOffset: Int,
    ) {
        jdbcTemplate.batchUpdate(
            """
            INSERT INTO seat (event_id, grade_id, section, row_label, seat_no, ordinal, status, created_at, updated_at)
            VALUES (?, ?, 'A', ?, ?, ?, 'AVAILABLE', now(), now())
            """.trimIndent(),
            (0 until count).map { n ->
                arrayOf<Any>(eventId, gradeId, (n / 30).toString(), (n % 30) + 1, n + ordinalOffset)
            },
        )
    }

    companion object {
        private val FIXED_HOLD_EXPIRES_AT: LocalDateTime = LocalDateTime.of(2030, 1, 1, 0, 0)
    }
}
