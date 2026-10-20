package com.ticketrush.payment.infrastructure

import com.ticketrush.payment.PaymentPolicyProperties
import com.ticketrush.support.TestClock
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.springframework.mock.env.MockEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test

// 규칙이 찾는 지표 이름이 앱이 내놓는 이름과 어긋나면 규칙이 조용히 죽는다. 규칙 동작 자체는 promtool 단위 테스트(alert-rules.test.yml)로 본다
class PaymentAlertRulesTest {
    @Test
    fun `알림 규칙이 쓰는 결제 지표 이름은 모두 실제로 노출된다`() {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val policy = PaymentPolicyProperties(staleClaimTimeout = Duration.ofSeconds(10), reclaimInterval = Duration.ofSeconds(5))
        PaymentRefundBacklogMetrics(mockk(), policy, TestClock()).bindTo(registry)
        PaymentGatewayInfoMetrics(MockEnvironment().withProperty(PAYMENT_GATEWAY_PROPERTY, "toss")).bindTo(registry)
        val exposed = registry.scrape()

        val used =
            Regex("payment_[a-z_]+")
                .findAll(Files.readString(Path.of("docker/prometheus/alert-rules.yml")))
                .map { it.value }
                .toSet()

        assertThat(used).contains("payment_refund_required", "payment_gateway_info")
        used.forEach { name -> assertThat(exposed).describedAs("규칙이 쓰는 지표: $name").contains(name) }
    }
}
