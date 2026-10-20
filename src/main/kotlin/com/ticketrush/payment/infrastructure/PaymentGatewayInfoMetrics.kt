package com.ticketrush.payment.infrastructure

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

// 값이 1인 정보성 게이지. 인스턴스마다 다른 type이 보이면 PG 구현이 섞인 것이다
@Component
class PaymentGatewayInfoMetrics(
    private val environment: Environment,
) : MeterBinder {
    override fun bindTo(registry: MeterRegistry) {
        Gauge
            .builder("payment.gateway.info") { 1.0 }
            .tag("type", PaymentGatewayType.from(environment).name.lowercase())
            .description("이 인스턴스가 쓰는 PG 구현(fake 또는 toss)")
            .register(registry)
    }
}
