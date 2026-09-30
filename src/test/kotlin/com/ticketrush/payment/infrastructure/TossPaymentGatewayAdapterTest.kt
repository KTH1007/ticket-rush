package com.ticketrush.payment.infrastructure

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import com.ticketrush.payment.TossProperties
import com.ticketrush.payment.domain.PaymentGatewayResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.web.client.RestClient
import java.util.Base64
import java.util.UUID
import kotlin.test.Test

class TossPaymentGatewayAdapterTest {
    companion object {
        @JvmField
        @RegisterExtension
        val wireMock: WireMockExtension = WireMockExtension.newInstance().build()
    }

    private fun adapter(): TossPaymentGatewayAdapter {
        val properties = TossProperties(baseUrl = wireMock.baseUrl(), secretKey = "test-secret-key")
        return TossPaymentGatewayAdapter(RestClient.create(), properties)
    }

    @Test
    fun `승인 응답의 status가 DONE이면 Approved를 반환한다`() {
        wireMock.stubFor(
            post(urlEqualTo("/v1/payments/confirm"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"paymentKey":"pk_test_1","orderId":"order-1","status":"DONE","totalAmount":10000}"""),
                ),
        )

        val result = adapter().charge("pk_test_1", "order-1", 10_000, UUID.randomUUID())

        assertThat(result).isInstanceOf(PaymentGatewayResult.Approved::class.java)
        assertThat((result as PaymentGatewayResult.Approved).pgTransactionId).isEqualTo("pk_test_1")
    }

    @Test
    fun `4xx 에러 응답이면 Declined를 반환한다`() {
        wireMock.stubFor(
            post(urlEqualTo("/v1/payments/confirm"))
                .willReturn(
                    aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"code":"REJECT_CARD_COMPANY","message":"카드사에서 거절했습니다"}"""),
                ),
        )

        val result = adapter().charge("pk_test_2", "order-2", 10_000, UUID.randomUUID())

        assertThat(result).isInstanceOf(PaymentGatewayResult.Declined::class.java)
        assertThat((result as PaymentGatewayResult.Declined).reason).contains("REJECT_CARD_COMPANY")
    }

    @Test
    fun `요청에 Idempotency-Key 헤더와 Basic 인증을 싣는다`() {
        wireMock.stubFor(
            post(urlEqualTo("/v1/payments/confirm"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"paymentKey":"pk_3","status":"DONE"}"""),
                ),
        )
        val idempotencyKey = UUID.randomUUID()

        adapter().charge("pk_3", "order-3", 10_000, idempotencyKey)

        val expectedAuth = "Basic " + Base64.getEncoder().encodeToString("test-secret-key:".toByteArray())
        wireMock.verify(
            postRequestedFor(urlEqualTo("/v1/payments/confirm"))
                .withHeader("Idempotency-Key", equalTo(idempotencyKey.toString()))
                .withHeader("Authorization", equalTo(expectedAuth))
                .withRequestBody(equalToJson("""{"paymentKey":"pk_3","orderId":"order-3","amount":10000}""")),
        )
    }
}
