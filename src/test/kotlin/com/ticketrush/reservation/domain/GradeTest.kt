package com.ticketrush.reservation.domain

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class GradeTest {
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

    private fun 등급(id: Long): Grade = Grade(id = id, eventId = 1L, name = "VIP", price = 200_000)
}
