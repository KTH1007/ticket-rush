package com.ticketrush.payment.domain

import org.assertj.core.api.Assertions.assertThat
import java.util.UUID
import kotlin.test.Test

class ChargeIdempotencyKeyTest {
    @Test
    fun `거절된 적이 없으면 예약 키를 그대로 쓴다`() {
        val reservationKey = UUID.randomUUID()

        assertThat(ChargeIdempotencyKey.of(reservationKey, 0)).isEqualTo(reservationKey)
    }

    @Test
    fun `같은 예약 키와 같은 거절 횟수에서는 항상 같은 키가 나온다`() {
        val reservationKey = UUID.randomUUID()

        assertThat(ChargeIdempotencyKey.of(reservationKey, 2)).isEqualTo(ChargeIdempotencyKey.of(reservationKey, 2))
    }

    @Test
    fun `거절 횟수가 다르면 다른 키가 나온다`() {
        val reservationKey = UUID.randomUUID()

        assertThat(ChargeIdempotencyKey.of(reservationKey, 1)).isNotEqualTo(ChargeIdempotencyKey.of(reservationKey, 2))
        assertThat(ChargeIdempotencyKey.of(reservationKey, 1)).isNotEqualTo(reservationKey)
    }

    @Test
    fun `환불 키와 같은 값이 나오지 않는다`() {
        val reservationKey = UUID.randomUUID()

        assertThat(ChargeIdempotencyKey.of(reservationKey, 1)).isNotEqualTo(RefundIdempotencyKey.of(reservationKey, 1))
    }

    @Test
    fun `유도 규칙이 바뀌면 재시도 중인 승인의 키가 달라지므로 값을 고정한다`() {
        val reservationKey = UUID.fromString("3f2b8c1e-9a4d-4e6f-8b7a-1c2d3e4f5a6b")

        assertThat(ChargeIdempotencyKey.of(reservationKey, 1)).isEqualTo(UUID.fromString("22d8e065-2cf7-3ee3-84f1-32c6d44dcd96"))
    }
}
