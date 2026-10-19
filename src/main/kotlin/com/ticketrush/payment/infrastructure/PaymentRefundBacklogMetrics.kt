package com.ticketrush.payment.infrastructure

import com.ticketrush.payment.PaymentPolicyProperties
import com.ticketrush.payment.domain.PaymentRepositoryPort
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger {}

private const val MILLIS_PER_SECOND = 1_000.0

// 사람이 처리해야 하는 환불 건수. 0보다 크면 고객이 돈만 나갔거나 환불을 못 받은 건이 있다는 뜻이라 알림 대상이다.
// 스크랩이 DB를 직접 부르면 풀이 포화될 때 스크랩이 막히고 DB 오류는 NaN이 돼 알림이 조용해지므로, 주기 갱신 값만 읽는다
@Component
class PaymentRefundBacklogMetrics(
    private val paymentRepository: PaymentRepositoryPort,
    private val policy: PaymentPolicyProperties,
    private val clock: Clock,
) : MeterBinder {
    private data class Snapshot(
        val required: Long,
        val exhausted: Long,
        val refreshedAt: Instant,
    )

    // 첫 갱신 전에는 기동 시각부터 경과를 센다
    @Volatile
    private var snapshot = Snapshot(required = 0, exhausted = 0, refreshedAt = clock.instant())

    override fun bindTo(registry: MeterRegistry) {
        Gauge
            .builder("payment.refund.required", this) { it.snapshot.required.toDouble() }
            .description("Toss는 승인했지만 예약이 만료돼 사람이 환불해야 하는데 아직 처리되지 않은 결제 수")
            .register(registry)
        Gauge
            .builder("payment.refund.exhausted", this) { it.snapshot.exhausted.toDouble() }
            .description("환불 시도를 모두 소진해 사람이 처리해야 하는 취소 결제 수")
            .register(registry)
        Gauge
            .builder("payment.refund.backlog.age", this) {
                Duration.between(it.snapshot.refreshedAt, clock.instant()).toMillis() / MILLIS_PER_SECOND
            }.baseUnit("seconds")
            .description("위 두 건수를 마지막으로 갱신한 뒤 지난 시간. 길어지면 건수를 믿을 수 없다")
            .register(registry)
    }

    // DB만 읽는 틱이라 겹침 가드는 필요 없다. 실패하면 마지막 값을 유지하고 경과 시간이 늘어난다
    @Scheduled(fixedRateString = "\${ticket-rush.payment.refund-backlog-refresh-interval}")
    @Suppress("TooGenericExceptionCaught")
    fun refresh() {
        try {
            snapshot =
                Snapshot(
                    required = paymentRepository.countRefundRequired(),
                    exhausted = paymentRepository.countExhaustedRefunds(policy.refundMaxAttempts),
                    refreshedAt = clock.instant(),
                )
        } catch (e: Exception) {
            logger.warn { "환불 미처리 건수를 갱신하지 못해 이전 값을 유지합니다: ${e.javaClass.simpleName} ${e.message}" }
        }
    }
}
