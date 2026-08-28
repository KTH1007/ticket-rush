package com.ticketrush.event.application

import com.ticketrush.event.domain.EventNotFoundException
import com.ticketrush.event.domain.EventRepositoryPort
import com.ticketrush.event.domain.GradeRepositoryPort
import com.ticketrush.support.TransactionalIntegrationTest
import com.ticketrush.support.공연_하나_저장
import com.ticketrush.support.등급_하나_저장
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.PageRequest
import kotlin.test.Test

class EventQueryServiceTest : TransactionalIntegrationTest() {
    @Autowired
    lateinit var eventRepository: EventRepositoryPort

    @Autowired
    lateinit var gradeRepository: GradeRepositoryPort

    @Autowired
    lateinit var eventQueryService: EventQueryService

    @Test
    fun `공연 목록을 페이지네이션으로 조회한다`() {
        // given
        eventRepository.공연_하나_저장(title = "아이유 콘서트")

        // when
        val result = eventQueryService.findEvents(PageRequest.of(0, 10))

        // then
        assertThat(result.content).extracting("title").containsExactly("아이유 콘서트")
        assertThat(result.totalElements).isEqualTo(1)
    }

    @Test
    fun `공연 상세 조회 시 등급 목록이 함께 반환된다`() {
        // given
        val event = eventRepository.공연_하나_저장()
        gradeRepository.등급_하나_저장(event, name = "VIP", price = 200_000)

        // when
        val result = eventQueryService.findEvent(event.id)

        // then
        assertThat(result.grades).extracting("name").containsExactly("VIP")
    }

    @Test
    fun `등급이 없는 공연은 빈 배열을 반환한다`() {
        // given
        val event = eventRepository.공연_하나_저장()

        // when
        val result = eventQueryService.findEvent(event.id)

        // then
        assertThat(result.grades).isEmpty()
    }

    @Test
    fun `존재하지 않는 id로 조회하면 EventNotFoundException이 발생한다`() {
        // when & then
        assertThatThrownBy { eventQueryService.findEvent(999L) }
            .isInstanceOf(EventNotFoundException::class.java)
    }
}
