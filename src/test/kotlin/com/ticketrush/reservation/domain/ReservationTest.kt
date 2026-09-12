package com.ticketrush.reservation.domain

import com.ticketrush.shared.PhoneHash
import org.assertj.core.api.Assertions.assertThat
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.Test

class ReservationTest {
    @Test
    fun `id가 같으면 동일한 엔티티로 판단한다`() {
        // given
        val reservation1 = 예약(id = 1L)
        val reservation2 = 예약(id = 1L)

        // when & then
        assertThat(reservation1).isEqualTo(reservation2)
    }

    @Test
    fun `id가 다르면 동일하지 않다`() {
        // given
        val reservation1 = 예약(id = 1L)
        val reservation2 = 예약(id = 2L)

        // when & then
        assertThat(reservation1).isNotEqualTo(reservation2)
    }

    @Test
    fun `아직 저장되지 않은 엔티티끼리는 동일하지 않다`() {
        // given
        val reservation1 = 예약(id = 0L)
        val reservation2 = 예약(id = 0L)

        // when & then
        assertThat(reservation1).isNotEqualTo(reservation2)
    }

    @Test
    fun `동일한 엔티티는 hashCode도 같다`() {
        // given
        val reservation1 = 예약(id = 1L)
        val reservation2 = 예약(id = 1L)

        // when & then
        assertThat(reservation1.hashCode()).isEqualTo(reservation2.hashCode())
    }

    private fun 예약(id: Long): Reservation =
        Reservation(
            id = id,
            eventId = 1L,
            phoneHash = PhoneHash(ByteArray(32) { 1 }),
            quantity = 1,
            amount = 100_000,
            holdToken = UUID.randomUUID(),
            idempotencyKey = UUID.randomUUID(),
            holdExpiresAt = LocalDateTime.of(2030, 1, 1, 0, 0),
        )
}
