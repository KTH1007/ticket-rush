package com.ticketrush.payment.infrastructure

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import com.github.tomakehurst.wiremock.stubbing.Scenario
import com.ticketrush.payment.TossProperties
import com.ticketrush.payment.domain.PaymentConflictException
import com.ticketrush.payment.domain.PaymentGatewayResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.web.client.RestClient
import java.util.UUID
import kotlin.test.Test

class TossPaymentGatewayAdapterIdempotencyTest {
    companion object {
        @JvmField
        @RegisterExtension
        val wireMock: WireMockExtension = WireMockExtension.newInstance().build()

        private const val PROCESSING_BODY = """{"code":"IDEMPOTENT_REQUEST_PROCESSING","message":"이전 멱등 요청이 처리중입니다."}"""
    }

    private val adapter =
        TossPaymentGatewayAdapter(
            RestClient.create(),
            TossProperties(baseUrl = wireMock.baseUrl(), secretKey = "test-secret-key"),
        )

    @Test
    fun `같은 Idempotency-Key로 두 번 승인을 요청하면 같은 Approved가 온다`() {
        val key = UUID.randomUUID()
        wireMock.stubFor(
            post(urlEqualTo("/v1/payments/confirm"))
                .withHeader("Idempotency-Key", equalTo(key.toString()))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"paymentKey":"pk_idem_1","status":"DONE"}"""),
                ),
        )

        val first = adapter.charge("pk_idem_1", "order-idem-1", 10_000, key)
        val second = adapter.charge("pk_idem_1", "order-idem-1", 10_000, key)

        assertThat(first).isEqualTo(PaymentGatewayResult.Approved("pk_idem_1"))
        assertThat(second).isEqualTo(first)
    }

    @Test
    fun `처리 중 409를 받은 뒤 같은 키로 재요청하면 승인 결과를 받는다`() {
        val key = UUID.randomUUID()
        stubConfirmInScenario(key, from = Scenario.STARTED, to = "processed", status = 409, body = PROCESSING_BODY)
        stubConfirmInScenario(key, from = "processed", to = null, status = 200, body = """{"paymentKey":"pk_idem_2","status":"DONE"}""")

        assertThatThrownBy { adapter.charge("pk_idem_2", "order-idem-2", 10_000, key) }
            .isInstanceOf(PaymentConflictException::class.java)
        val retried = adapter.charge("pk_idem_2", "order-idem-2", 10_000, key)

        assertThat(retried).isEqualTo(PaymentGatewayResult.Approved("pk_idem_2"))
        wireMock.verify(
            2,
            postRequestedFor(urlEqualTo("/v1/payments/confirm"))
                .withHeader("Idempotency-Key", equalTo(key.toString())),
        )
    }

    private fun stubConfirmInScenario(
        key: UUID,
        from: String,
        to: String?,
        status: Int,
        body: String,
    ) {
        val stub =
            post(urlEqualTo("/v1/payments/confirm"))
                .inScenario("idempotent-processing")
                .whenScenarioStateIs(from)
                .withHeader("Idempotency-Key", equalTo(key.toString()))
                .willReturn(
                    aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body),
                )
        wireMock.stubFor(if (to != null) stub.willSetStateTo(to) else stub)
    }
}
