package com.ticketrush.event.domain

import com.ticketrush.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDateTime
import kotlin.test.Test

class EventRepositoryTest : IntegrationTest() {
    @Autowired
    lateinit var eventRepository: EventRepositoryPort

    @Test
    fun `Event 저장 후 조회`() {
        // given
        val event =
            Event(
                title = "아이유 콘서트",
                venue = "잠실종합운동장",
                description = "2026 아이유 콘서트",
                opensAt = LocalDateTime.of(2026, 9, 1, 10, 0),
                startsAt = LocalDateTime.of(2026, 9, 20, 19, 0),
            )

        // when
        val saved = eventRepository.save(event)
        val found = eventRepository.findById(saved.id)

        // then
        requireNotNull(found)
        assertThat(found.title).isEqualTo("아이유 콘서트")
        assertThat(found.venue).isEqualTo("잠실종합운동장")
        assertThat(found.description).isEqualTo("2026 아이유 콘서트")
        assertThat(found.opensAt).isEqualTo(event.opensAt)
        assertThat(found.startsAt).isEqualTo(event.startsAt)
    }
}
