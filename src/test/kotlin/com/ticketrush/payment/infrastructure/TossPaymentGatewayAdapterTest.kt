package com.ticketrush.payment.infrastructure

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import com.ticketrush.payment.TossProperties
import com.ticketrush.payment.domain.PaymentConflictException
import com.ticketrush.payment.domain.PaymentGatewayResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.RestClient
import java.time.LocalDateTime
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
    fun `승인 응답에 approvedAt이 있으면 Approved에 서울 시각으로 담긴다`() {
        stubConfirmDone(approvedAt = "2026-10-01T10:15:30+09:00")

        val result = adapter().charge("pk", "order-1", 10_000, UUID.randomUUID())

        assertThat(result)
            .isEqualTo(PaymentGatewayResult.Approved(pgTransactionId = "pk", approvedAt = LocalDateTime.of(2026, 10, 1, 10, 15, 30)))
    }

    @Test
    fun `approvedAt의 오프셋이 달라도 같은 순간이면 같은 서울 시각이 된다`() {
        stubConfirmDone(approvedAt = "2026-10-01T01:15:30+00:00")

        val result = adapter().charge("pk", "order-1", 10_000, UUID.randomUUID())

        assertThat(result)
            .isEqualTo(PaymentGatewayResult.Approved(pgTransactionId = "pk", approvedAt = LocalDateTime.of(2026, 10, 1, 10, 15, 30)))
    }

    @Test
    fun `승인 응답에 approvedAt이 없으면 approvedAt은 null이고 Approved를 반환한다`() {
        stubConfirmDone(approvedAt = null)

        val result = adapter().charge("pk", "order-1", 10_000, UUID.randomUUID())

        assertThat(result).isEqualTo(PaymentGatewayResult.Approved(pgTransactionId = "pk", approvedAt = null))
    }

    @Test
    fun `approvedAt이 형식에 맞지 않아도 예외 없이 approvedAt은 null이고 Approved를 반환한다`() {
        stubConfirmDone(approvedAt = "어제 오후 세 시")

        val result = adapter().charge("pk", "order-1", 10_000, UUID.randomUUID())

        assertThat(result).isEqualTo(PaymentGatewayResult.Approved(pgTransactionId = "pk", approvedAt = null))
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

    // 429(호출 제한)와 408(시간 초과)은 PG가 요청을 처리하지 못한 일시 오류다. 확정 거절로 굳히면 결제가 FAILED가 되고 홀드가 줄어든다
    @Test
    fun `429 호출 제한은 거절로 확정하지 않고 나중에 다시 시도하도록 PaymentConflictException을 던진다`() {
        wireMock.stubFor(
            post(urlEqualTo("/v1/payments/confirm"))
                .willReturn(
                    aResponse()
                        .withStatus(429)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"code":"TOO_MANY_REQUESTS","message":"요청이 너무 많습니다"}"""),
                ),
        )

        assertThatThrownBy { adapter().charge("pk_rate", "order-rate", 10_000, UUID.randomUUID()) }
            .isInstanceOf(PaymentConflictException::class.java)
    }

    @Test
    fun `408 시간 초과도 거절로 확정하지 않고 PaymentConflictException을 던진다`() {
        wireMock.stubFor(post(urlEqualTo("/v1/payments/confirm")).willReturn(aResponse().withStatus(408)))

        assertThatThrownBy { adapter().charge("pk_timeout", "order-timeout", 10_000, UUID.randomUUID()) }
            .isInstanceOf(PaymentConflictException::class.java)
    }

    // toss 프로필이 아닐 때는 키가 필요 없어 설정 기본값이 빈 문자열이므로, 프로필을 켜고 키를 빠뜨리면 기동 때 바로 드러나야 한다
    @Test
    fun `시크릿 키가 비어 있으면 어댑터를 만들 때 실패한다`() {
        assertThatThrownBy {
            TossPaymentGatewayAdapter(RestClient.create(), TossProperties(baseUrl = wireMock.baseUrl(), secretKey = " "))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    // 거절 사유는 이력에 저장되고 클라이언트 응답에도 실린다. JSON이 아닌 본문(프록시의 HTML 등)을 그대로 싣지 않는다
    @Test
    fun `4xx 본문이 JSON이 아니면 원문 대신 상태 코드만 사유로 쓴다`() {
        wireMock.stubFor(
            post(urlEqualTo("/v1/payments/confirm"))
                .willReturn(
                    aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "text/html")
                        .withBody("<html><body>blocked by upstream proxy ${"x".repeat(500)}</body></html>"),
                ),
        )

        val result = adapter().charge("pk_test_html", "order-html", 10_000, UUID.randomUUID())

        assertThat((result as PaymentGatewayResult.Declined).reason).isEqualTo("HTTP 400")
    }

    @Test
    fun `403 REJECT_CARD_COMPANY도 Declined를 반환한다`() {
        wireMock.stubFor(
            post(urlEqualTo("/v1/payments/confirm"))
                .willReturn(
                    aResponse()
                        .withStatus(403)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"code":"REJECT_CARD_COMPANY","message":"카드사에서 거절했습니다"}"""),
                ),
        )

        val result = adapter().charge("pk_test_7", "order-7", 10_000, UUID.randomUUID())

        assertThat(result).isInstanceOf(PaymentGatewayResult.Declined::class.java)
        assertThat((result as PaymentGatewayResult.Declined).reason).contains("REJECT_CARD_COMPANY")
    }

    @Test
    fun `401 인증 실패는 Declined가 아니라 예외를 던진다`() {
        wireMock.stubFor(
            post(urlEqualTo("/v1/payments/confirm"))
                .willReturn(
                    aResponse()
                        .withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"code":"UNAUTHORIZED_KEY","message":"인증되지 않은 시크릿 키 혹은 클라이언트 키 입니다."}"""),
                ),
        )

        assertThatThrownBy { adapter().charge("pk_test_8", "order-8", 10_000, UUID.randomUUID()) }
            .isInstanceOf(HttpClientErrorException.Unauthorized::class.java)
    }

    @Test
    fun `409 IDEMPOTENT_REQUEST_PROCESSING이면 Declined가 아니라 PaymentConflictException을 던진다`() {
        wireMock.stubFor(
            post(urlEqualTo("/v1/payments/confirm"))
                .willReturn(
                    aResponse()
                        .withStatus(409)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"code":"IDEMPOTENT_REQUEST_PROCESSING","message":"이전 멱등 요청이 처리중입니다."}"""),
                ),
        )

        assertThatThrownBy { adapter().charge("pk_test_5", "order-5", 10_000, UUID.randomUUID()) }
            .isInstanceOf(PaymentConflictException::class.java)
    }

    @Test
    fun `409여도 code가 IDEMPOTENT_REQUEST_PROCESSING이 아니면 Declined를 반환한다`() {
        wireMock.stubFor(
            post(urlEqualTo("/v1/payments/confirm"))
                .willReturn(
                    aResponse()
                        .withStatus(409)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"code":"DUPLICATED_ORDER_ID","message":"이미 사용된 주문번호입니다."}"""),
                ),
        )

        val result = adapter().charge("pk_test_6", "order-6", 10_000, UUID.randomUUID())

        assertThat(result).isInstanceOf(PaymentGatewayResult.Declined::class.java)
        assertThat((result as PaymentGatewayResult.Declined).reason).contains("DUPLICATED_ORDER_ID")
    }

    @Test
    fun `5xx 에러 응답이면 예외를 던진다`() {
        wireMock.stubFor(
            post(urlEqualTo("/v1/payments/confirm"))
                .willReturn(
                    aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"code":"FAILED_INTERNAL_SYSTEM_PROCESSING","message":"일시적인 오류가 발생했습니다"}"""),
                ),
        )

        assertThatThrownBy { adapter().charge("pk_test_4", "order-4", 10_000, UUID.randomUUID()) }
            .isInstanceOf(HttpServerErrorException::class.java)
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

    @Test
    fun `취소 성공 응답이면 Approved를 반환한다`() {
        wireMock.stubFor(
            post(urlEqualTo("/v1/payments/pk_refund_1/cancel"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"paymentKey":"pk_refund_1","status":"CANCELED"}"""),
                ),
        )

        val result = adapter().refund("pk_refund_1", 10_000, UUID.randomUUID())

        assertThat(result).isInstanceOf(PaymentGatewayResult.Approved::class.java)
    }

    private fun stubConfirmDone(approvedAt: String?) {
        val approvedAtField = approvedAt?.let { ""","approvedAt":"$it"""" }.orEmpty()
        wireMock.stubFor(
            post(urlEqualTo("/v1/payments/confirm"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"paymentKey":"pk","status":"DONE"$approvedAtField}"""),
                ),
        )
    }
}
