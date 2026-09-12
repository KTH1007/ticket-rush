package com.ticketrush.reservation.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.test.Test

class SeatSelectionTest {
    @Test
    fun `좌석 1개로 만들 수 있다`() {
        // when
        val selection = SeatSelection(listOf(1L))

        // then
        assertThat(selection.seatIds).containsExactly(1L)
    }

    @Test
    fun `좌석 2개로 만들 수 있다`() {
        // when
        val selection = SeatSelection(listOf(1L, 2L))

        // then
        assertThat(selection.seatIds).containsExactly(1L, 2L)
    }

    @Test
    fun `좌석 0개로는 만들 수 없다`() {
        // when & then
        assertThatThrownBy { SeatSelection(emptyList()) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `좌석 3개 이상으로는 만들 수 없다`() {
        // when & then
        assertThatThrownBy { SeatSelection(listOf(1L, 2L, 3L)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `중복된 좌석 id로는 만들 수 없다`() {
        // when & then
        assertThatThrownBy { SeatSelection(listOf(1L, 1L)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
