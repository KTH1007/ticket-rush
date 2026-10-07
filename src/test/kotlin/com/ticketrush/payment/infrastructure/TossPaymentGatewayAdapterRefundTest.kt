package com.ticketrush.payment.infrastructure

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
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
import java.util.UUID
import kotlin.test.Test

class TossPaymentGatewayAdapterRefundTest {
    companion object {
        private const val PAYMENT_KEY = "pk-refund"

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

    @Test
    fun `이미 취소됨 400이고 조회가 CANCELED이면 돈이 이미 돌아갔으므로 Approved를 반환한다`() {
        stubCancelError(400, "ALREADY_CANCELED_PAYMENT", "이미 취소된 결제 입니다")
        stubInquiry("CANCELED")

        val result = adapter.refund(PAYMENT_KEY, 20_000, UUID.randomUUID())

        assertThat(result).isEqualTo(PaymentGatewayResult.Approved(pgTransactionId = PAYMENT_KEY))
    }

    @Test
    fun `이미 취소됨 400인데 조회가 DONE이면 환불된 게 아니므로 Declined를 반환한다`() {
        stubCancelError(400, "ALREADY_CANCELED_PAYMENT", "이미 취소된 결제 입니다")
        stubInquiry("DONE")

        val result = adapter.refund(PAYMENT_KEY, 20_000, UUID.randomUUID())

        assertThat(result).isInstanceOf(PaymentGatewayResult.Declined::class.java)
    }

    @Test
    fun `이미 취소됨 400인데 조회가 PARTIAL_CANCELED이면 전액 환불이 아니므로 Declined를 반환한다`() {
        stubCancelError(400, "ALREADY_CANCELED_PAYMENT", "이미 취소된 결제 입니다")
        stubInquiry("PARTIAL_CANCELED")

        val result = adapter.refund(PAYMENT_KEY, 20_000, UUID.randomUUID())

        assertThat(result).isInstanceOf(PaymentGatewayResult.Declined::class.java)
    }

    @Test
    fun `이미 취소됨 400인데 조회가 404이면 Declined를 반환한다`() {
        stubCancelError(400, "ALREADY_CANCELED_PAYMENT", "이미 취소된 결제 입니다")
        wireMock.stubFor(
            get(urlEqualTo("/v1/payments/$PAYMENT_KEY"))
                .willReturn(jsonResponse(404, """{"code":"NOT_FOUND_PAYMENT","message":"존재하지 않는 결제 입니다."}""")),
        )

        val result = adapter.refund(PAYMENT_KEY, 20_000, UUID.randomUUID())

        assertThat(result).isInstanceOf(PaymentGatewayResult.Declined::class.java)
    }

    @Test
    fun `이미 취소됨 400 뒤 조회가 5xx이면 판정을 못 하므로 예외를 전파한다`() {
        stubCancelError(400, "ALREADY_CANCELED_PAYMENT", "이미 취소된 결제 입니다")
        wireMock.stubFor(
            get(urlEqualTo("/v1/payments/$PAYMENT_KEY"))
                .willReturn(jsonResponse(500, """{"code":"COMMON_ERROR","message":"일시적인 오류"}""")),
        )

        assertThatThrownBy { adapter.refund(PAYMENT_KEY, 20_000, UUID.randomUUID()) }
            .isInstanceOf(HttpServerErrorException::class.java)
    }

    @Test
    fun `이미 취소됨이 아닌 400은 조회 없이 Declined를 반환한다`() {
        stubCancelError(400, "INVALID_REQUEST", "잘못된 요청입니다")

        val result = adapter.refund(PAYMENT_KEY, 20_000, UUID.randomUUID())

        assertThat(result).isInstanceOf(PaymentGatewayResult.Declined::class.java)
        wireMock.verify(0, getRequestedFor(urlEqualTo("/v1/payments/$PAYMENT_KEY")))
    }

    @Test
    fun `409 IDEMPOTENT_REQUEST_PROCESSING이면 Declined가 아니라 PaymentConflictException을 던진다`() {
        stubCancelError(409, "IDEMPOTENT_REQUEST_PROCESSING", "이전 멱등 요청이 처리중입니다.")

        assertThatThrownBy { adapter.refund(PAYMENT_KEY, 20_000, UUID.randomUUID()) }
            .isInstanceOf(PaymentConflictException::class.java)
    }

    // 충돌로 던지면 시도 횟수와 갱신 시각이 그대로라 재시도 스케줄러가 매 틱 같은 키로 Toss를 다시 부른다. 실패로 세야 키가 바뀌고 5분 간격과 한도가 걸린다
    @Test
    fun `429 호출 제한은 환불 실패로 세도록 Declined를 반환한다`() {
        stubCancelError(429, "TOO_MANY_REQUESTS", "요청이 너무 많습니다")

        val result = adapter.refund(PAYMENT_KEY, 20_000, UUID.randomUUID())

        assertThat(result).isInstanceOf(PaymentGatewayResult.Declined::class.java)
        assertThat((result as PaymentGatewayResult.Declined).reason).contains("TOO_MANY_REQUESTS")
    }

    @Test
    fun `401 인증 실패는 Declined가 아니라 예외를 던진다`() {
        stubCancelError(401, "UNAUTHORIZED_KEY", "인증되지 않은 시크릿 키 혹은 클라이언트 키 입니다.")

        assertThatThrownBy { adapter.refund(PAYMENT_KEY, 20_000, UUID.randomUUID()) }
            .isInstanceOf(HttpClientErrorException.Unauthorized::class.java)
    }

    @Test
    fun `403 NOT_CANCELABLE_AMOUNT는 Declined를 반환한다`() {
        stubCancelError(403, "NOT_CANCELABLE_AMOUNT", "취소 할 수 없는 금액 입니다.")

        val result = adapter.refund(PAYMENT_KEY, 20_000, UUID.randomUUID())

        assertThat(result).isInstanceOf(PaymentGatewayResult.Declined::class.java)
    }

    @Test
    fun `취소 응답이 PARTIAL_CANCELED이면 전액 환불이 아니므로 Declined를 반환한다`() {
        wireMock.stubFor(
            post(urlEqualTo("/v1/payments/$PAYMENT_KEY/cancel"))
                .willReturn(jsonResponse(200, """{"paymentKey":"$PAYMENT_KEY","status":"PARTIAL_CANCELED"}""")),
        )

        val result = adapter.refund(PAYMENT_KEY, 20_000, UUID.randomUUID())

        assertThat(result).isInstanceOf(PaymentGatewayResult.Declined::class.java)
    }

    @Test
    fun `409여도 code가 IDEMPOTENT_REQUEST_PROCESSING이 아니면 Declined를 반환한다`() {
        stubCancelError(409, "SOME_OTHER_CONFLICT", "다른 충돌")

        val result = adapter.refund(PAYMENT_KEY, 20_000, UUID.randomUUID())

        assertThat(result).isInstanceOf(PaymentGatewayResult.Declined::class.java)
    }

    @Test
    fun `code가 ALREADY_CANCELED_PAYMENT여도 400이 아니면 조회 없이 Declined를 반환한다`() {
        stubCancelError(403, "ALREADY_CANCELED_PAYMENT", "이미 취소된 결제 입니다")

        val result = adapter.refund(PAYMENT_KEY, 20_000, UUID.randomUUID())

        assertThat(result).isInstanceOf(PaymentGatewayResult.Declined::class.java)
        wireMock.verify(0, getRequestedFor(urlEqualTo("/v1/payments/$PAYMENT_KEY")))
    }

    @Test
    fun `취소 호출 자체가 5xx이면 예외를 전파한다`() {
        stubCancelError(500, "COMMON_ERROR", "일시적인 오류")

        assertThatThrownBy { adapter.refund(PAYMENT_KEY, 20_000, UUID.randomUUID()) }
            .isInstanceOf(HttpServerErrorException::class.java)
    }

    private fun stubCancelError(
        status: Int,
        code: String,
        message: String,
    ) {
        wireMock.stubFor(
            post(urlEqualTo("/v1/payments/$PAYMENT_KEY/cancel"))
                .willReturn(jsonResponse(status, """{"code":"$code","message":"$message"}""")),
        )
    }

    private fun stubInquiry(status: String) {
        wireMock.stubFor(
            get(urlEqualTo("/v1/payments/$PAYMENT_KEY"))
                .willReturn(jsonResponse(200, """{"paymentKey":"$PAYMENT_KEY","status":"$status"}""")),
        )
    }

    private fun jsonResponse(
        status: Int,
        body: String,
    ) = aResponse()
        .withStatus(status)
        .withHeader("Content-Type", "application/json")
        .withBody(body)
}
