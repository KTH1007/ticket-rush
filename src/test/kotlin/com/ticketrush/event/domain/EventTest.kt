package com.ticketrush.event.domain

import org.assertj.core.api.Assertions.assertThat
import java.time.LocalDateTime
import kotlin.test.Test

class EventTest {
    @Test
    fun `id가 같으면 동일한 엔티티로 판단한다`() {
        // given
        val event1 = 공연(id = 1L)
        val event2 = 공연(id = 1L)

        // when & then
        assertThat(event1).isEqualTo(event2)
    }

    @Test
    fun `id가 다르면 동일하지 않다`() {
        // given
        val event1 = 공연(id = 1L)
        val event2 = 공연(id = 2L)

        // when & then
        assertThat(event1).isNotEqualTo(event2)
    }

    @Test
    fun `아직 저장되지 않은 엔티티끼리는 동일하지 않다`() {
        // given
        val event1 = 공연(id = 0L)
        val event2 = 공연(id = 0L)

        // when & then
        assertThat(event1).isNotEqualTo(event2)
    }

    @Test
    fun `동일한 엔티티는 hashCode도 같다`() {
        // given
        val event1 = 공연(id = 1L)
        val event2 = 공연(id = 1L)

        // when & then
        assertThat(event1.hashCode()).isEqualTo(event2.hashCode())
    }

    private fun 공연(id: Long): Event =
        Event(
            id = id,
            title = "아이유 콘서트",
            venue = "잠실종합운동장",
            opensAt = LocalDateTime.of(2026, 9, 1, 10, 0),
            startsAt = LocalDateTime.of(2026, 9, 20, 19, 0),
        )
}
