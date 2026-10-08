package com.ticketrush.payment.infrastructure

import com.ticketrush.payment.domain.PaymentRepositoryPort
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class PaymentRefundRequiredMetricsTest {
    private val paymentRepository = mockk<PaymentRepositoryPort>()
    private val registry = SimpleMeterRegistry()

    @Test
    fun `환불 필요 건수를 게이지로 노출하고 읽을 때마다 저장소의 최신 값을 따라간다`() {
        every { paymentRepository.countRefundRequired() } returns 2L
        PaymentRefundRequiredMetrics(paymentRepository).bindTo(registry)

        assertThat(registry.get("payment.refund.required").gauge().value()).isEqualTo(2.0)

        every { paymentRepository.countRefundRequired() } returns 0L
        assertThat(registry.get("payment.refund.required").gauge().value()).isEqualTo(0.0)
    }
}
