package com.ticketrush.payment.infrastructure

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import com.ticketrush.payment.TossProperties
import com.ticketrush.payment.domain.PaymentInquiryResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.RestClient
import java.time.LocalDateTime
import java.util.Base64
import kotlin.test.Test

class TossPaymentGatewayAdapterInquiryTest {
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
    fun `조회 응답의 status가 DONE이면 approvedAt을 서울 시각으로 담은 Done을 반환한다`() {
        stubInquiry("pk-done", status = "DONE", approvedAt = "2026-10-01T10:15:30+09:00")

        val result = adapter().inquire("pk-done")

        assertThat(result)
            .isEqualTo(PaymentInquiryResult.Done(pgTransactionId = "pk-done", approvedAt = LocalDateTime.of(2026, 10, 1, 10, 15, 30)))
    }

    @Test
    fun `approvedAt의 오프셋이 달라도 같은 순간이면 같은 서울 시각이 된다`() {
        stubInquiry("pk-done", status = "DONE", approvedAt = "2026-10-01T01:15:30+00:00")

        val result = adapter().inquire("pk-done")

        assertThat(result)
            .isEqualTo(PaymentInquiryResult.Done(pgTransactionId = "pk-done", approvedAt = LocalDateTime.of(2026, 10, 1, 10, 15, 30)))
    }

    @Test
    fun `approvedAt이 없거나 형식에 맞지 않아도 예외 없이 approvedAt은 null인 Done을 반환한다`() {
        stubInquiry("pk-no-time", status = "DONE", approvedAt = null)
        stubInquiry("pk-bad-time", status = "DONE", approvedAt = "어제 오후 세 시")

        assertThat(adapter().inquire("pk-no-time")).isEqualTo(PaymentInquiryResult.Done("pk-no-time", approvedAt = null))
        assertThat(adapter().inquire("pk-bad-time")).isEqualTo(PaymentInquiryResult.Done("pk-bad-time", approvedAt = null))
    }

    @ParameterizedTest
    @ValueSource(strings = ["IN_PROGRESS", "ABORTED", "EXPIRED", "CANCELED"])
    fun `DONE이 아닌 status는 그 status를 담은 NotApproved를 반환한다`(status: String) {
        stubInquiry("pk-$status", status = status)

        val result = adapter().inquire("pk-$status")

        assertThat(result).isEqualTo(PaymentInquiryResult.NotApproved(status))
    }

    @Test
    fun `404 NOT_FOUND_PAYMENT는 NotFound를 반환한다`() {
        wireMock.stubFor(
            get(urlEqualTo("/v1/payments/pk-missing"))
                .willReturn(
                    aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"code":"NOT_FOUND_PAYMENT","message":"존재하지 않는 결제 입니다."}"""),
                ),
        )

        assertThat(adapter().inquire("pk-missing")).isEqualTo(PaymentInquiryResult.NotFound)
    }

    @Test
    fun `404여도 code가 NOT_FOUND_PAYMENT가 아니면 NotFound로 단정하지 않고 예외를 던진다`() {
        wireMock.stubFor(
            get(urlEqualTo("/v1/payments/pk-odd"))
                .willReturn(aResponse().withStatus(404).withBody("<html>Not Found</html>")),
        )

        assertThatThrownBy { adapter().inquire("pk-odd") }
            .isInstanceOf(HttpClientErrorException.NotFound::class.java)
    }

    @Test
    fun `401 인증 실패는 NotFound가 아니라 예외를 다시 던진다`() {
        wireMock.stubFor(
            get(urlEqualTo("/v1/payments/pk-unauthorized"))
                .willReturn(
                    aResponse()
                        .withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"code":"UNAUTHORIZED_KEY","message":"인증되지 않은 시크릿 키 혹은 클라이언트 키 입니다."}"""),
                ),
        )

        assertThatThrownBy { adapter().inquire("pk-unauthorized") }
            .isInstanceOf(HttpClientErrorException.Unauthorized::class.java)
    }

    @Test
    fun `403 등 그 밖의 4xx도 승인 여부를 알 수 없으므로 예외를 던진다`() {
        wireMock.stubFor(
            get(urlEqualTo("/v1/payments/pk-forbidden"))
                .willReturn(
                    aResponse()
                        .withStatus(403)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"code":"FORBIDDEN_REQUEST","message":"허용되지 않은 요청입니다."}"""),
                ),
        )

        assertThatThrownBy { adapter().inquire("pk-forbidden") }
            .isInstanceOf(HttpClientErrorException.Forbidden::class.java)
    }

    @Test
    fun `5xx 에러 응답이면 예외를 전파한다`() {
        wireMock.stubFor(
            get(urlEqualTo("/v1/payments/pk-500"))
                .willReturn(
                    aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"code":"COMMON_ERROR","message":"일시적인 오류가 발생했습니다."}"""),
                ),
        )

        assertThatThrownBy { adapter().inquire("pk-500") }
            .isInstanceOf(HttpServerErrorException::class.java)
    }

    @Test
    fun `조회 요청은 Basic 인증을 싣는다`() {
        stubInquiry("pk-auth", status = "DONE")

        adapter().inquire("pk-auth")

        val expectedAuth = "Basic " + Base64.getEncoder().encodeToString("test-secret-key:".toByteArray())
        wireMock.verify(
            getRequestedFor(urlEqualTo("/v1/payments/pk-auth"))
                .withHeader("Authorization", equalTo(expectedAuth)),
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
                        .withBody("""{"paymentKey":"$paymentKey","orderId":"order-1","status":"$status"$approvedAtField}"""),
                ),
        )
    }
}
