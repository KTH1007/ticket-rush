package com.ticketrush.payment.infrastructure

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlMatching
import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import com.ticketrush.payment.TossHttpConfig
import com.ticketrush.payment.TossProperties
import com.ticketrush.payment.domain.PaymentConflictException
import com.ticketrush.payment.domain.PaymentGatewayResult
import com.ticketrush.payment.domain.PaymentGatewayUnavailableException
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.springboot.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.EnableAspectJAutoProxy
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.RestClientException
import java.util.UUID
import kotlin.test.Test

// @TestConfiguration으로 두면 TicketRushApplication의 컴포넌트 스캔에서 제외된다
@TestConfiguration
@EnableAspectJAutoProxy(proxyTargetClass = true)
@EnableConfigurationProperties(TossProperties::class)
@Import(CircuitBreakerAutoConfiguration::class)
class TossTestConfig

@ActiveProfiles("toss")
@SpringBootTest(classes = [TossTestConfig::class, TossHttpConfig::class, TossPaymentGatewayAdapter::class])
class TossPaymentGatewayAdapterCircuitBreakerTest {
    companion object {
        // 서킷의 minimumNumberOfCalls(10)보다 많게
        private const val CALLS_PAST_MINIMUM = 16

        @JvmField
        @RegisterExtension
        val wireMock: WireMockExtension = WireMockExtension.newInstance().build()

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("ticket-rush.toss.base-url") { wireMock.baseUrl() }
            registry.add("ticket-rush.toss.secret-key") { "test-secret-key" }
        }
    }

    @Autowired
    lateinit var adapter: TossPaymentGatewayAdapter

    @Autowired
    lateinit var circuitBreakerRegistry: CircuitBreakerRegistry

    // 서킷은 컨텍스트 안의 싱글톤이라 이전 테스트가 열어 둔 상태를 비워야 한다
    @BeforeEach
    fun resetCircuitBreaker() {
        circuitBreakerRegistry.circuitBreaker("paymentGateway").reset()
    }

    @Test
    fun `연속 실패로 서킷이 열리면 이후 호출은 WireMock에 안 가고 PaymentGatewayUnavailableException으로 즉시 실패한다`() {
        wireMock.stubFor(
            post(urlEqualTo("/v1/payments/confirm"))
                .willReturn(aResponse().withStatus(500)),
        )

        // minimumNumberOfCalls(10)번 실패하면 서킷이 열린다. 그 전의 5xx도 호출자에게는 같은 예외로 온다
        repeat(10) {
            assertThatThrownBy { adapter.charge("pk-$it", "order-$it", 10_000, UUID.randomUUID()) }
                .isInstanceOf(PaymentGatewayUnavailableException::class.java)
                .hasCauseInstanceOf(HttpServerErrorException::class.java)
        }

        assertThatThrownBy { adapter.charge("pk-open", "order-open", 10_000, UUID.randomUUID()) }
            .isInstanceOfSatisfying(PaymentGatewayUnavailableException::class.java) { assertThat(it.serverResponded).isFalse() }
            .hasCauseInstanceOf(CallNotPermittedException::class.java)

        assertThat(wireMock.allServeEvents).hasSize(10)
    }

    @Test
    fun `서킷이 열리면 조회와 환불도 WireMock에 안 가고 PaymentGatewayUnavailableException으로 즉시 실패한다`() {
        circuitBreakerRegistry.circuitBreaker("paymentGateway").transitionToOpenState()

        assertThatThrownBy { adapter.inquire("pk-open") }
            .isInstanceOf(PaymentGatewayUnavailableException::class.java)
            .hasCauseInstanceOf(CallNotPermittedException::class.java)
        assertThatThrownBy { adapter.refund("pk-open", 10_000, UUID.randomUUID()) }
            .isInstanceOf(PaymentGatewayUnavailableException::class.java)
            .hasCauseInstanceOf(CallNotPermittedException::class.java)

        assertThat(wireMock.allServeEvents).isEmpty()
    }

    @Test
    fun `409 IDEMPOTENT_REQUEST_PROCESSING은 몇 번을 받아도 서킷을 열지 않는다`() {
        wireMock.stubFor(
            post(urlEqualTo("/v1/payments/confirm"))
                .willReturn(
                    aResponse()
                        .withStatus(409)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"code":"IDEMPOTENT_REQUEST_PROCESSING","message":"이전 멱등 요청이 처리중입니다."}"""),
                ),
        )

        // minimumNumberOfCalls(10)를 넘겨도 실패로 집계되지 않아야 한다. 마지막 호출까지 서킷이 닫혀 있어 WireMock에 간다
        repeat(CALLS_PAST_MINIMUM) {
            assertThatThrownBy {
                adapter.charge("pk-$it", "order-$it", 10_000, UUID.randomUUID())
            }.isInstanceOf(PaymentConflictException::class.java)
        }

        assertThat(wireMock.allServeEvents).hasSize(CALLS_PAST_MINIMUM)
    }

    @Test
    fun `4xx 거절은 몇 번을 받아도 서킷을 열지 않는다`() {
        wireMock.stubFor(
            post(urlEqualTo("/v1/payments/confirm"))
                .willReturn(
                    aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"code":"REJECT_CARD_PAYMENT","message":"한도초과 혹은 잔액부족으로 결제에 실패했습니다."}"""),
                ),
        )

        // minimumNumberOfCalls(10)를 넘겨도 거절은 PG 장애가 아니라서 실패로 집계되지 않아야 한다
        repeat(CALLS_PAST_MINIMUM) {
            val result = adapter.charge("pk-$it", "order-$it", 10_000, UUID.randomUUID())
            assertThat(result).isInstanceOf(PaymentGatewayResult.Declined::class.java)
        }

        assertThat(wireMock.allServeEvents).hasSize(CALLS_PAST_MINIMUM)
    }

    // refund도 같은 서킷 인스턴스를 쓴다. 환불 장애가 승인도 막는 것이 의도된 동작이다
    @Test
    fun `환불의 연속 실패가 서킷을 열어 승인도 WireMock에 안 가고 즉시 실패한다`() {
        wireMock.stubFor(
            post(urlMatching("/v1/payments/.+/cancel"))
                .willReturn(aResponse().withStatus(500)),
        )

        // 환불의 5xx는 어댑터가 변환하지 않고 그대로 던진다(호출이 나가 결과를 모르므로 호출 측이 실패로 센다)
        repeat(10) {
            assertThatThrownBy { adapter.refund("pk-$it", 10_000, UUID.randomUUID()) }
                .isInstanceOf(HttpServerErrorException::class.java)
        }

        assertThatThrownBy { adapter.charge("pk-after", "order-after", 10_000, UUID.randomUUID()) }
            .isInstanceOf(PaymentGatewayUnavailableException::class.java)
            .hasCauseInstanceOf(CallNotPermittedException::class.java)
        assertThat(wireMock.allServeEvents).hasSize(10)
    }

    // 3초 읽기 타임아웃이 실제로 걸려야 느린 PG 하나가 요청 스레드를 오래 잡지 못한다
    @Test
    fun `응답이 4초 지연되면 3초 타임아웃으로 PaymentGatewayUnavailableException이 된다`() {
        wireMock.stubFor(
            post(urlEqualTo("/v1/payments/confirm"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(4_000)
                        .withBody("""{"paymentKey":"pk","status":"DONE"}"""),
                ),
        )

        val startedAt = System.nanoTime()
        assertThatThrownBy { adapter.charge("pk-slow", "order-slow", 10_000, UUID.randomUUID()) }
            .isInstanceOf(PaymentGatewayUnavailableException::class.java)
            .hasCauseInstanceOf(RestClientException::class.java)
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

        assertThat(elapsedMillis).describedAs("경과 시간(ms)").isBetween(2_500L, 3_900L)
    }
}
