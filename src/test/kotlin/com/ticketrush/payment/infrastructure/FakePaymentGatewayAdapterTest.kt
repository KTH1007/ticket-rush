package com.ticketrush.payment.infrastructure

import com.ticketrush.payment.domain.PaymentGatewayResult
import org.assertj.core.api.Assertions.assertThat
import java.time.Duration
import java.util.UUID
import kotlin.test.Test

class FakePaymentGatewayAdapterTest {
    @Test
    fun `지연을 주면 그만큼 기다린 뒤 승인한다`() {
        val adapter = FakePaymentGatewayAdapter(Duration.ofMillis(50))
        val startedAt = System.nanoTime()

        val result = adapter.charge("payment-key", "order-id", 1_000, UUID.randomUUID())

        assertThat(result).isInstanceOf(PaymentGatewayResult.Approved::class.java)
        assertThat((System.nanoTime() - startedAt) / 1_000_000).isGreaterThanOrEqualTo(50)
    }

    @Test
    fun `지연이 0이면 바로 승인한다`() {
        val result = FakePaymentGatewayAdapter(Duration.ZERO).charge("payment-key", "order-id", 1_000, UUID.randomUUID())

        assertThat(result).isInstanceOf(PaymentGatewayResult.Approved::class.java)
    }
}
