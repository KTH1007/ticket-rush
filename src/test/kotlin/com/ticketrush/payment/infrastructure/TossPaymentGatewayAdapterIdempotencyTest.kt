package com.ticketrush.payment.infrastructure

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import com.github.tomakehurst.wiremock.stubbing.Scenario
import com.ticketrush.payment.domain.ChargeIdempotencyKey
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

    // Toss는 에러 응답도 같은 키로 재생한다. 거절 횟수를 섞은 새 키로 요청해야 거절 뒤 재시도가 승인된다
    @Test
    fun `거절된 키는 같은 에러를 재생하고 거절 횟수를 섞은 새 키로 요청하면 승인된다`() {
        val reservationKey = UUID.randomUUID()
        stubConfirmForKey(reservationKey, 400, """{"code":"REJECT_CARD_PAYMENT","message":"한도초과 혹은 잔액부족으로 결제에 실패했습니다."}""")
        stubConfirmForKey(ChargeIdempotencyKey.of(reservationKey, 1), 200, """{"paymentKey":"pk_replay","status":"DONE"}""")

        val declined = adapter.charge("pk_replay", "order-replay", 10_000, ChargeIdempotencyKey.of(reservationKey, 0))
        val replayed = adapter.charge("pk_replay", "order-replay", 10_000, ChargeIdempotencyKey.of(reservationKey, 0))
        val retried = adapter.charge("pk_replay", "order-replay", 10_000, ChargeIdempotencyKey.of(reservationKey, 1))

        assertThat(declined).isInstanceOf(PaymentGatewayResult.Declined::class.java)
        assertThat(replayed).isEqualTo(declined)
        assertThat(retried).isEqualTo(PaymentGatewayResult.Approved("pk_replay"))
    }

    private fun stubConfirmForKey(
        key: UUID,
        status: Int,
        body: String,
    ) {
        wireMock.stubFor(
            post(urlEqualTo("/v1/payments/confirm"))
                .withHeader("Idempotency-Key", equalTo(key.toString()))
                .willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)),
        )
    }
}
