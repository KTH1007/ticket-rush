package com.ticketrush.event.domain

import com.ticketrush.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import java.time.LocalDateTime
import kotlin.test.Test

class GradeRepositoryTest : IntegrationTest() {
    @Autowired
    lateinit var eventRepository: EventRepositoryPort

    @Autowired
    lateinit var gradeRepository: GradeRepositoryPort

    private fun 공연_하나_저장(): Event =
        eventRepository.save(
            Event(
                title = "아이유 콘서트",
                venue = "잠실종합운동장",
                opensAt = LocalDateTime.of(2026, 9, 1, 10, 0),
                startsAt = LocalDateTime.of(2026, 9, 20, 19, 0),
            ),
        )

    @Test
    fun `Grade가 Event를 참조하며 저장됨`() {
        // given
        val event = 공연_하나_저장()

        // when
        val saved = gradeRepository.save(Grade(event = event, name = "VIP", price = 200_000))

        // then
        assertThat(saved.event.id).isEqualTo(event.id)
        assertThat(saved.name).isEqualTo("VIP")
        assertThat(saved.price).isEqualTo(200_000)
    }

    @Test
    fun `uk_grade_event_name 위반 시(같은 공연에 같은 등급명) 예외 발생`() {
        // given
        val event = 공연_하나_저장()
        gradeRepository.save(Grade(event = event, name = "VIP", price = 200_000))

        // when & then
        assertThatThrownBy {
            gradeRepository.save(Grade(event = event, name = "VIP", price = 150_000))
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }
}
