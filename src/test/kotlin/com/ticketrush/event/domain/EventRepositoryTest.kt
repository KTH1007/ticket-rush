package com.ticketrush.event.domain

import com.ticketrush.support.IntegrationTest
import com.ticketrush.support.공연_하나_저장
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.Test

class EventRepositoryTest : IntegrationTest() {
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
}
