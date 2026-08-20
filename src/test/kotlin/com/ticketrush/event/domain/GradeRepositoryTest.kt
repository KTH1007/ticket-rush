package com.ticketrush.event.domain

import com.ticketrush.support.IntegrationTest
import com.ticketrush.support.공연_하나_저장
import com.ticketrush.support.등급_하나_저장
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import kotlin.test.Test

class GradeRepositoryTest : IntegrationTest() {
    @Autowired
    lateinit var eventRepository: EventRepositoryPort

    @Autowired
    lateinit var gradeRepository: GradeRepositoryPort

    @Test
    fun `Grade가 Event를 참조하며 저장됨`() {
        // given
        val event = eventRepository.공연_하나_저장()

        // when
        val saved = gradeRepository.등급_하나_저장(event)

        // then
        assertThat(saved.event.id).isEqualTo(event.id)
        assertThat(saved.name).isEqualTo("VIP")
        assertThat(saved.price).isEqualTo(200_000)
    }

    @Test
    fun `uk_grade_event_name 위반 시(같은 공연에 같은 등급명) 예외 발생`() {
        // given
        val event = eventRepository.공연_하나_저장()
        gradeRepository.등급_하나_저장(event)

        // when & then
        assertThatThrownBy {
            gradeRepository.등급_하나_저장(event, price = 150_000)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }
}
