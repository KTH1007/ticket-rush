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
import java.util.UUID
import kotlin.test.Test

class TossPaymentGatewayAdapterRefundTest {
    companion object {
        @JvmField
        @RegisterExtension
        val wireMock: WireMockExtension = WireMockExtension.newInstance().build()
    }

    private val adapter =
        TossPaymentGatewayAdapter(
            RestClient.create(),
            TossProperties(baseUrl = wireMock.baseUrl(), secretKey = "test-secret-key"),
        )

    @Test
    fun `환불 요청에 Idempotency-Key 헤더와 cancelReason, cancelAmount를 싣는다`() {
        wireMock.stubFor(
            post(urlEqualTo("/v1/payments/pk-refund/cancel"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"paymentKey":"pk-refund","status":"CANCELED"}"""),
                ),
        )
        val idempotencyKey = UUID.randomUUID()

        val result = adapter.refund("pk-refund", 20_000, idempotencyKey)

        assertThat(result).isEqualTo(PaymentGatewayResult.Approved(pgTransactionId = "pk-refund"))
        wireMock.verify(
            postRequestedFor(urlEqualTo("/v1/payments/pk-refund/cancel"))
                .withHeader("Idempotency-Key", equalTo(idempotencyKey.toString()))
                .withRequestBody(equalToJson("""{"cancelReason":"사용자 취소 요청","cancelAmount":20000}""")),
        )
    }
}
