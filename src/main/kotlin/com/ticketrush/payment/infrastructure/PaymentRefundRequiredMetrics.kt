package com.ticketrush.payment.infrastructure

import com.ticketrush.payment.domain.PaymentRepositoryPort
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import org.springframework.stereotype.Component

// 환불이 필요한데 사람이 아직 처리하지 않은 결제 수. 0보다 크면 고객이 돈만 나가고 티켓이 없는 건이 있다는 뜻이라 알림 대상이다
@Component
class PaymentRefundRequiredMetrics(
    private val paymentRepository: PaymentRepositoryPort,
) : MeterBinder {
    override fun bindTo(registry: MeterRegistry) {
        Gauge
            .builder("payment.refund.required") { paymentRepository.countRefundRequired().toDouble() }
            .description("Toss는 승인했지만 예약이 만료돼 사람이 환불해야 하는데 아직 처리되지 않은 결제 수")
            .register(registry)
    }
}
