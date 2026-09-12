package com.ticketrush.event.domain

import org.assertj.core.api.Assertions.assertThat
import java.time.LocalDateTime
import kotlin.test.Test

class GradeTest {
    private val event =
        Event(
            id = 1L,
            title = "아이유 콘서트",
            venue = "잠실종합운동장",
            opensAt = LocalDateTime.of(2026, 9, 1, 10, 0),
            startsAt = LocalDateTime.of(2026, 9, 20, 19, 0),
        )

    @Test
    fun `id가 같으면 동일한 엔티티로 판단한다`() {
        // given
        val grade1 = 등급(id = 1L)
        val grade2 = 등급(id = 1L)

        // when & then
        assertThat(grade1).isEqualTo(grade2)
    }

    @Test
    fun `id가 다르면 동일하지 않다`() {
        // given
        val grade1 = 등급(id = 1L)
        val grade2 = 등급(id = 2L)

        // when & then
        assertThat(grade1).isNotEqualTo(grade2)
    }

    @Test
    fun `아직 저장되지 않은 엔티티끼리는 동일하지 않다`() {
        // given
        val grade1 = 등급(id = 0L)
        val grade2 = 등급(id = 0L)

        // when & then
        assertThat(grade1).isNotEqualTo(grade2)
    }

    @Test
    fun `동일한 엔티티는 hashCode도 같다`() {
        // given
        val grade1 = 등급(id = 1L)
        val grade2 = 등급(id = 1L)

        // when & then
        assertThat(grade1.hashCode()).isEqualTo(grade2.hashCode())
    }

    private fun 등급(id: Long): Grade = Grade(id = id, event = event, name = "VIP", price = 200_000)
}
