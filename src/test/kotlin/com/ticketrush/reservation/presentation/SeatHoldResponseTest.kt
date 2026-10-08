package com.ticketrush.reservation.presentation

import com.ticketrush.reservation.domain.Reservation
import com.ticketrush.shared.PhoneHash
import org.assertj.core.api.Assertions.assertThat
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.Test

class SeatHoldResponseTest {
    @Test
    fun `orderId는 reservation의 idempotencyKey 문자열이다`() {
        val idempotencyKey = UUID.randomUUID()
        val reservation =
            Reservation(
                id = 1L,
                eventId = 1L,
                phoneHash = PhoneHash(ByteArray(32) { 1 }),
                quantity = 1,
                amount = 100_000,
                holdToken = UUID.randomUUID(),
                idempotencyKey = idempotencyKey,
                holdExpiresAt = LocalDateTime.of(2030, 1, 1, 0, 0),
            )

        val response = SeatHoldResponse.from(reservation)

        assertThat(response.orderId).isEqualTo(idempotencyKey.toString())
    }
}
