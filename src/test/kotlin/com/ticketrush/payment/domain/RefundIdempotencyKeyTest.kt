package com.ticketrush.payment.domain

import org.assertj.core.api.Assertions.assertThat
import java.util.UUID
import kotlin.test.Test

class RefundIdempotencyKeyTest {
    @Test
    fun `같은 예약 키와 같은 시도 횟수에서는 항상 같은 환불 키가 나온다`() {
        val reservationKey = UUID.randomUUID()

        assertThat(RefundIdempotencyKey.of(reservationKey, 2)).isEqualTo(RefundIdempotencyKey.of(reservationKey, 2))
    }

    @Test
    fun `시도 횟수가 다르면 다른 환불 키가 나온다`() {
        val reservationKey = UUID.randomUUID()

        assertThat(RefundIdempotencyKey.of(reservationKey, 0)).isNotEqualTo(RefundIdempotencyKey.of(reservationKey, 1))
    }

    @Test
    fun `다른 예약 키에서는 다른 환불 키가 나온다`() {
        assertThat(RefundIdempotencyKey.of(UUID.randomUUID(), 0)).isNotEqualTo(RefundIdempotencyKey.of(UUID.randomUUID(), 0))
    }

    @Test
    fun `유도 규칙이 바뀌면 재시도 중인 환불의 키가 달라지므로 값을 고정한다`() {
        val reservationKey = UUID.fromString("3f2b8c1e-9a4d-4e6f-8b7a-1c2d3e4f5a6b")

        assertThat(RefundIdempotencyKey.of(reservationKey, 0)).isEqualTo(UUID.fromString("cf4111e1-b41b-34c5-a0c3-e905499b75e0"))
        assertThat(RefundIdempotencyKey.of(reservationKey, 1)).isEqualTo(UUID.fromString("fad0d1d9-b0b2-36ac-a410-083dc162cb25"))
    }
}
