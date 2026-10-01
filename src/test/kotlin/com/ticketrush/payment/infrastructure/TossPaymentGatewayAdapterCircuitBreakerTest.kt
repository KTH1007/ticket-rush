package com.ticketrush.payment.infrastructure

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import com.ticketrush.payment.TossHttpConfig
import com.ticketrush.payment.TossProperties
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.springboot.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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

    @Test
    fun `연속 실패로 서킷이 열리면 이후 호출은 WireMock에 안 가고 즉시 실패한다`() {
        wireMock.stubFor(
            post(urlEqualTo("/v1/payments/confirm"))
                .willReturn(aResponse().withStatus(500)),
        )

        // CLOSED 상태에서는 500 예외가 호출자에게 그대로 전파되므로 통계용으로 무시한다
        repeat(10) {
            try {
                adapter.charge("pk-$it", "order-$it", 10_000, UUID.randomUUID())
            } catch (expected: HttpServerErrorException) {
            }
        }

        assertThatThrownBy {
            adapter.charge("pk-open", "order-open", 10_000, UUID.randomUUID())
        }.isInstanceOf(CallNotPermittedException::class.java)

        assertThat(wireMock.allServeEvents).hasSizeLessThan(11)
    }
}
