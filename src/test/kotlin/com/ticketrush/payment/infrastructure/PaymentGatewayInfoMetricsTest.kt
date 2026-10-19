package com.ticketrush.payment.infrastructure

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.springframework.mock.env.MockEnvironment
import kotlin.test.Test

class PaymentGatewayInfoMetricsTest {
    private fun metrics(gateway: String?) =
        PaymentGatewayInfoMetrics(MockEnvironment().apply { gateway?.let { withProperty(PAYMENT_GATEWAY_PROPERTY, it) } })

    @Test
    fun `설정된 PG 구현이 type 태그로 값 1인 게이지에 실린다`() {
        listOf("fake", "toss").forEach { gateway ->
            val registry = SimpleMeterRegistry()

            metrics(gateway).bindTo(registry)

            val gauges = registry.find("payment.gateway.info").gauges()
            assertThat(gauges).hasSize(1)
            assertThat(gauges.single().id.getTag("type")).isEqualTo(gateway)
            assertThat(gauges.single().value()).isEqualTo(1.0)
        }
    }

    @Test
    fun `대소문자가 달라도 type 태그는 소문자로 통일된다`() {
        val registry = SimpleMeterRegistry()

        metrics("TOSS").bindTo(registry)

        assertThat(registry.find("payment.gateway.info").tag("type", "toss").gauge()).isNotNull()
    }

    // 알림 규칙이 이 이름과 라벨로 찾는다
    @Test
    fun `프로메테우스에는 payment_gateway_info와 type 라벨로 노출된다`() {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

        metrics("toss").bindTo(registry)

        assertThat(registry.scrape()).contains("payment_gateway_info").contains("type=\"toss\"")
    }

    @Test
    fun `설정이 없으면 지표를 묶을 때 실패한다`() {
        assertThatThrownBy { metrics(null).bindTo(SimpleMeterRegistry()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(PAYMENT_GATEWAY_PROPERTY)
    }
}
