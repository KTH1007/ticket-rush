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
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.EnableAspectJAutoProxy
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.HttpServerErrorException
import java.util.UUID
import kotlin.test.Test

// @SpringBootApplication/@EnableAutoConfiguration은 안 쓴다. 그걸 쓰면 spring-modulith가
// 클래스패스 전체에서 @EnableAutoConfiguration 붙은 클래스를 찾아 그 패키지를 무관한
// TicketRushApplication 컨텍스트의 AutoConfigurationPackages에 끼워넣어서, PaymentJpaRepository가
// 두 번 스캔되고 BeanDefinitionOverrideException이 나는 실제 버그를 겪었다.
// 서킷브레이커 자동설정만 명시적으로 @Import해서 그 문제를 피한다.
@Configuration
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
