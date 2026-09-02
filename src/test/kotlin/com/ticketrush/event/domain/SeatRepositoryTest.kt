package com.ticketrush.event.domain

import com.ticketrush.support.IntegrationTest
import com.ticketrush.support.공연_하나_저장
import com.ticketrush.support.등급_하나_저장
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.tuple
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.Test

class SeatRepositoryTest : IntegrationTest() {
    @Autowired
    lateinit var eventRepository: EventRepositoryPort

    @Autowired
    lateinit var gradeRepository: GradeRepositoryPort

    @Autowired
    lateinit var seatRepository: SeatRepositoryPort

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
}
