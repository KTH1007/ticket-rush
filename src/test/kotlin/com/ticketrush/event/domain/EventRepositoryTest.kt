package com.ticketrush.event.domain

import com.ticketrush.support.TransactionalIntegrationTest
import com.ticketrush.support.공연_하나_저장
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.PageRequest
import kotlin.test.Test

class EventRepositoryTest : TransactionalIntegrationTest() {
    @Autowired
    lateinit var eventRepository: EventRepositoryPort

    @Test
    fun `Event 저장 후 조회`() {
        // given
        val saved = eventRepository.공연_하나_저장(description = "2026 아이유 콘서트")

        // when
        val found = eventRepository.findById(saved.id)

        // then
        requireNotNull(found)
        assertThat(found.title).isEqualTo("아이유 콘서트")
        assertThat(found.venue).isEqualTo("잠실종합운동장")
        assertThat(found.description).isEqualTo("2026 아이유 콘서트")
        assertThat(found.opensAt).isEqualTo(saved.opensAt)
        assertThat(found.startsAt).isEqualTo(saved.startsAt)
    }

    @Test
    fun `여러 Event를 페이지네이션으로 조회`() {
        // given
        eventRepository.공연_하나_저장(title = "공연1")
        eventRepository.공연_하나_저장(title = "공연2")
        eventRepository.공연_하나_저장(title = "공연3")

        // when
        val page = eventRepository.findAll(PageRequest.of(0, 2))

        // then
        assertThat(page.content).hasSize(2)
        assertThat(page.totalElements).isEqualTo(3)
        assertThat(page.totalPages).isEqualTo(2)
    }
}
