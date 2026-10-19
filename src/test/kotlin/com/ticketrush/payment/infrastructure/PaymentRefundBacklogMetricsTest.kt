package com.ticketrush.payment.infrastructure

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.ticketrush.payment.PaymentPolicyProperties
import com.ticketrush.payment.domain.PaymentRepositoryPort
import com.ticketrush.support.TestClock
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.slf4j.LoggerFactory
import java.time.Duration
import kotlin.test.Test

class PaymentRefundBacklogMetricsTest {
    private val paymentRepository = mockk<PaymentRepositoryPort>()
    private val clock = TestClock()
    private val registry = SimpleMeterRegistry()
    private val logs = ListAppender<ILoggingEvent>()
    private val metricsLogger = LoggerFactory.getLogger("com.ticketrush.payment.infrastructure") as Logger

    @BeforeEach
    fun captureLogs() {
        logs.start()
        metricsLogger.addAppender(logs)
    }

    @AfterEach
    fun releaseLogs() {
        metricsLogger.detachAppender(logs)
        logs.stop()
    }

    @Test
    fun `갱신하면 환불 필요와 소진 건수를 게이지가 따라간다`() {
        every { paymentRepository.countRefundRequired() } returns 2L
        every { paymentRepository.countExhaustedRefunds(5) } returns 3L
        val metrics = 지표().also { it.bindTo(registry) }

        metrics.refresh()

        assertThat(값("payment.refund.required")).isEqualTo(2.0)
        assertThat(값("payment.refund.exhausted")).isEqualTo(3.0)

        every { paymentRepository.countRefundRequired() } returns 0L
        every { paymentRepository.countExhaustedRefunds(5) } returns 0L
        metrics.refresh()

        assertThat(값("payment.refund.required")).isEqualTo(0.0)
        assertThat(값("payment.refund.exhausted")).isEqualTo(0.0)
    }

    @Test
    fun `게이지를 읽어도 저장소를 부르지 않는다`() {
        every { paymentRepository.countRefundRequired() } returns 1L
        every { paymentRepository.countExhaustedRefunds(5) } returns 1L
        val metrics = 지표().also { it.bindTo(registry) }
        metrics.refresh()

        repeat(3) {
            값("payment.refund.required")
            값("payment.refund.exhausted")
            값("payment.refund.backlog.age")
        }

        verify(exactly = 1) { paymentRepository.countRefundRequired() }
        verify(exactly = 1) { paymentRepository.countExhaustedRefunds(any()) }
    }

    @Test
    fun `갱신이 실패하면 마지막 값을 유지하고 마지막 성공 뒤 경과 시간이 늘어난다`() {
        every { paymentRepository.countRefundRequired() } returns 2L
        every { paymentRepository.countExhaustedRefunds(5) } returns 3L
        val metrics = 지표().also { it.bindTo(registry) }
        metrics.refresh()
        clock.advanceSeconds(20)

        every { paymentRepository.countRefundRequired() } throws IllegalStateException("db down")
        metrics.refresh()

        assertThat(값("payment.refund.required")).isEqualTo(2.0)
        assertThat(값("payment.refund.exhausted")).isEqualTo(3.0)
        assertThat(값("payment.refund.backlog.age")).isEqualTo(20.0)
        clock.advanceSeconds(40)
        assertThat(값("payment.refund.backlog.age")).isEqualTo(60.0)
        assertThat(logs.list.filter { it.level == Level.WARN }.map { it.formattedMessage })
            .anyMatch { it.contains("이전 값을 유지") && it.contains("db down") }
    }

    @Test
    fun `소진 건수 조회가 실패해도 환불 필요 건수만 먼저 바뀌지 않는다`() {
        every { paymentRepository.countRefundRequired() } returns 1L
        every { paymentRepository.countExhaustedRefunds(5) } returns 1L
        val metrics = 지표().also { it.bindTo(registry) }
        metrics.refresh()

        every { paymentRepository.countRefundRequired() } returns 4L
        every { paymentRepository.countExhaustedRefunds(5) } throws IllegalStateException("db down")
        metrics.refresh()

        assertThat(값("payment.refund.required")).isEqualTo(1.0)
        assertThat(값("payment.refund.exhausted")).isEqualTo(1.0)
    }

    @Test
    fun `갱신이 다시 성공하면 경과 시간이 0으로 돌아온다`() {
        every { paymentRepository.countRefundRequired() } throws IllegalStateException("db down")
        val metrics = 지표().also { it.bindTo(registry) }
        clock.advanceSeconds(90)
        metrics.refresh()
        assertThat(값("payment.refund.backlog.age")).isEqualTo(90.0)

        every { paymentRepository.countRefundRequired() } returns 0L
        every { paymentRepository.countExhaustedRefunds(5) } returns 0L
        metrics.refresh()

        assertThat(값("payment.refund.backlog.age")).isEqualTo(0.0)
    }

    @Test
    fun `첫 갱신 전에는 건수가 0이고 경과 시간은 기동 시각부터 센다`() {
        지표().bindTo(registry)
        clock.advanceSeconds(7)

        assertThat(값("payment.refund.required")).isEqualTo(0.0)
        assertThat(값("payment.refund.exhausted")).isEqualTo(0.0)
        assertThat(값("payment.refund.backlog.age")).isEqualTo(7.0)
        verify(exactly = 0) { paymentRepository.countRefundRequired() }
    }

    @Test
    fun `소진 건수는 설정의 환불 한도로 센다`() {
        every { paymentRepository.countRefundRequired() } returns 0L
        every { paymentRepository.countExhaustedRefunds(3) } returns 9L
        val metrics = 지표(maxAttempts = 3).also { it.bindTo(registry) }

        metrics.refresh()

        assertThat(값("payment.refund.exhausted")).isEqualTo(9.0)
    }

    // 알림 규칙이 이 이름으로 지표를 찾는다. 이름이 바뀌면 규칙이 조용히 죽는다
    @Test
    fun `프로메테우스에는 알림 규칙이 쓰는 이름으로 노출된다`() {
        val prometheus = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        지표().bindTo(prometheus)

        val exposed = prometheus.scrape()

        assertThat(exposed)
            .contains("payment_refund_required")
            .contains("payment_refund_exhausted")
            .contains("payment_refund_backlog_age_seconds")
    }

    private fun 지표(maxAttempts: Int = 5): PaymentRefundBacklogMetrics =
        PaymentRefundBacklogMetrics(
            paymentRepository,
            PaymentPolicyProperties(
                staleClaimTimeout = Duration.ofSeconds(10),
                reclaimInterval = Duration.ofSeconds(5),
                refundMaxAttempts = maxAttempts,
            ),
            clock,
        )

    private fun 값(name: String): Double = registry.get(name).gauge().value()
}
