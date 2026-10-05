package com.ticketrush.payment.infrastructure

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import com.ticketrush.payment.TossProperties
import com.ticketrush.payment.domain.PaymentConflictException
import com.ticketrush.payment.domain.PaymentGatewayResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.RestClient
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.Test

// 승인 API가 "이미 처리됨" 계열 400을 주면 Toss는 이미 승인했을 수 있어, 거절로 확정하지 않고 조회로 판정한다
class TossPaymentGatewayAdapterAlreadyProcessedTest {
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

    @ParameterizedTest
    @ValueSource(strings = ["ALREADY_PROCESSED_PAYMENT", "DUPLICATED_REQUEST"])
    fun `이미 처리됨 400 뒤 조회가 DONE이면 approvedAt을 담은 Approved를 반환한다`(code: String) {
        stubConfirmBadRequest(code)
        stubInquiry("pk-1", status = "DONE", approvedAt = "2026-10-01T10:15:30+09:00")

        val result = adapter.charge("pk-1", "order-1", 10_000, UUID.randomUUID())

        assertThat(result)
            .isEqualTo(PaymentGatewayResult.Approved(pgTransactionId = "pk-1", approvedAt = LocalDateTime.of(2026, 10, 1, 10, 15, 30)))
    }

    @ParameterizedTest
    @ValueSource(strings = ["ALREADY_PROCESSED_PAYMENT", "DUPLICATED_REQUEST"])
    fun `이미 처리됨 400 뒤 조회가 DONE이 아니면 그 status를 담은 Declined를 반환한다`(code: String) {
        stubConfirmBadRequest(code)
        stubInquiry("pk-2", status = "ABORTED")

        val result = adapter.charge("pk-2", "order-2", 10_000, UUID.randomUUID())

        assertThat(result).isInstanceOf(PaymentGatewayResult.Declined::class.java)
        assertThat((result as PaymentGatewayResult.Declined).reason).contains("ABORTED")
    }

    @Test
    fun `이미 처리됨 400 뒤 조회가 404 NOT_FOUND_PAYMENT이면 Declined를 반환한다`() {
        stubConfirmBadRequest("ALREADY_PROCESSED_PAYMENT")
        wireMock.stubFor(
            get(urlEqualTo("/v1/payments/pk-3"))
                .willReturn(
                    aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"code":"NOT_FOUND_PAYMENT","message":"존재하지 않는 결제 입니다."}"""),
                ),
        )

        val result = adapter.charge("pk-3", "order-3", 10_000, UUID.randomUUID())

        assertThat(result).isInstanceOf(PaymentGatewayResult.Declined::class.java)
    }

    @Test
    fun `이미 처리됨 400 뒤 조회가 500이면 거절로 확정하지 않고 예외를 전파한다`() {
        stubConfirmBadRequest("ALREADY_PROCESSED_PAYMENT")
        wireMock.stubFor(get(urlEqualTo("/v1/payments/pk-4")).willReturn(aResponse().withStatus(500)))

        assertThatThrownBy { adapter.charge("pk-4", "order-4", 10_000, UUID.randomUUID()) }
            .isInstanceOf(HttpServerErrorException::class.java)
    }

    @Test
    fun `400 ALREADY_PROCESSING_REQUEST는 조회 없이 PaymentConflictException을 던진다`() {
        stubConfirmBadRequest("ALREADY_PROCESSING_REQUEST")

        assertThatThrownBy { adapter.charge("pk-5", "order-5", 10_000, UUID.randomUUID()) }
            .isInstanceOf(PaymentConflictException::class.java)
        wireMock.verify(0, getRequestedFor(urlEqualTo("/v1/payments/pk-5")))
    }

    @Test
    fun `그 밖의 400은 조회 없이 여전히 Declined를 반환한다`() {
        stubConfirmBadRequest("REJECT_CARD_COMPANY")

        val result = adapter.charge("pk-6", "order-6", 10_000, UUID.randomUUID())

        assertThat(result).isInstanceOf(PaymentGatewayResult.Declined::class.java)
        assertThat((result as PaymentGatewayResult.Declined).reason).contains("REJECT_CARD_COMPANY")
        wireMock.verify(0, getRequestedFor(urlEqualTo("/v1/payments/pk-6")))
    }

    @Test
    fun `400이 아니면 code가 이미 처리됨 계열이어도 조회 없이 Declined를 반환한다`() {
        wireMock.stubFor(
            post(urlEqualTo("/v1/payments/confirm"))
                .willReturn(
                    aResponse()
                        .withStatus(403)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"code":"ALREADY_PROCESSED_PAYMENT","message":"이미 처리된 결제입니다."}"""),
                ),
        )

        val result = adapter.charge("pk-7", "order-7", 10_000, UUID.randomUUID())

        assertThat(result).isInstanceOf(PaymentGatewayResult.Declined::class.java)
        wireMock.verify(0, getRequestedFor(urlEqualTo("/v1/payments/pk-7")))
    }

    private fun stubConfirmBadRequest(code: String) {
        wireMock.stubFor(
            post(urlEqualTo("/v1/payments/confirm"))
                .willReturn(
                    aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"code":"$code","message":"테스트 메시지"}"""),
                ),
        )
    }

    private fun stubInquiry(
        paymentKey: String,
        status: String,
        approvedAt: String? = null,
    ) {
        val approvedAtField = approvedAt?.let { ""","approvedAt":"$it"""" }.orEmpty()
        wireMock.stubFor(
            get(urlEqualTo("/v1/payments/$paymentKey"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"paymentKey":"$paymentKey","status":"$status"$approvedAtField}"""),
                ),
        )
    }
}
