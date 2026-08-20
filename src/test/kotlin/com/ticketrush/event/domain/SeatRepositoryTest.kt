package com.ticketrush.event.domain

import com.ticketrush.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
    lateinit var jdbcTemplate: JdbcTemplate

    private fun 공연_하나_저장(): Event =
        eventRepository.save(
            Event(
                title = "아이유 콘서트",
                venue = "잠실종합운동장",
                opensAt = LocalDateTime.of(2026, 9, 1, 10, 0),
                startsAt = LocalDateTime.of(2026, 9, 20, 19, 0),
            ),
        )

    private fun 등급_하나_저장(event: Event): Grade = gradeRepository.save(Grade(event = event, name = "VIP", price = 200_000))

    @Test
    fun `Seat가 Event, Grade를 참조하며 저장됨`() {
        // given
        val event = 공연_하나_저장()
        val grade = 등급_하나_저장(event)

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
        val event = 공연_하나_저장()
        val grade = 등급_하나_저장(event)
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
        val event = 공연_하나_저장()
        val grade = 등급_하나_저장(event)
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
        val event = 공연_하나_저장()
        val grade = 등급_하나_저장(event)

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
}
