package com.ticketrush.reservation.domain

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class SeatTest {
    @Test
    fun `id가 같으면 동일한 엔티티로 판단한다`() {
        // given
        val seat1 = 좌석(id = 1L)
        val seat2 = 좌석(id = 1L)

        // when & then
        assertThat(seat1).isEqualTo(seat2)
    }

    @Test
    fun `id가 다르면 동일하지 않다`() {
        // given
        val seat1 = 좌석(id = 1L)
        val seat2 = 좌석(id = 2L)

        // when & then
        assertThat(seat1).isNotEqualTo(seat2)
    }

    @Test
    fun `아직 저장되지 않은 엔티티끼리는 동일하지 않다`() {
        // given
        val seat1 = 좌석(id = 0L)
        val seat2 = 좌석(id = 0L)

        // when & then
        assertThat(seat1).isNotEqualTo(seat2)
    }

    @Test
    fun `동일한 엔티티는 hashCode도 같다`() {
        // given
        val seat1 = 좌석(id = 1L)
        val seat2 = 좌석(id = 1L)

        // when & then
        assertThat(seat1.hashCode()).isEqualTo(seat2.hashCode())
    }

    private fun 좌석(id: Long): Seat = Seat(id = id, eventId = 1L, gradeId = 1L, section = "A", rowLabel = "1", seatNo = 1, ordinal = 0)
}
