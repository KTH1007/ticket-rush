package com.ticketrush.e2e

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import org.assertj.core.api.Assertions.assertThat
import org.springframework.test.context.TestPropertySource
import kotlin.test.Test

// E21: 다른 테스트의 PG 호출 이력이 서킷 집계에 섞이지 않게 별도 컨텍스트에서 돈다
@TestPropertySource(properties = ["e2e.context=gateway-circuit"])
class PaymentGatewayCircuitE2eTest : E2eTest() {
    @Test
    fun `E21 PG 장애가 이어지면 서킷이 열려 PG를 부르지 않고 503으로 막고 복구되면 다시 승인한다`() {
        val event = seed.openEvent(seatCount = FAILING_PAYMENTS + 2)
        toss.stubFor(post(urlEqualTo("/v1/payments/confirm")).atPriority(1).willReturn(aResponse().withStatus(500)))

        val failures =
            (0 until FAILING_PAYMENTS).map { index ->
                val held = holdSeat(event, index)
                api.pay(held.hold, nextPaymentKey(), held.ip)
            }

        failures.forEach {
            assertThat(it.status).`as`(it.body).isEqualTo(503)
            assertThat(it.code).isEqualTo("PAYMENT_GATEWAY_UNAVAILABLE")
        }
        val reachedPg = toss.findAll(postRequestedFor(urlEqualTo("/v1/payments/confirm"))).size
        assertThat(reachedPg).`as`("서킷이 열린 뒤에는 PG까지 가지 않는다").isLessThan(FAILING_PAYMENTS)

        toss.removeStubMapping(toss.listAllStubMappings().mappings.first { it.priority == 1 })
        Thread.sleep(CIRCUIT_OPEN_WAIT_MILLIS)
        val held = holdSeat(event, FAILING_PAYMENTS)
        val recovered = api.pay(held.hold, nextPaymentKey(), held.ip)

        assertThat(recovered.status).`as`("복구 뒤 결제: ${recovered.body}").isEqualTo(HTTP_CREATED)
    }

    private companion object {
        // 최소 호출 수(10)를 넘겨 실패율 50%를 채운다
        const val FAILING_PAYMENTS = 12
        const val CIRCUIT_OPEN_WAIT_MILLIS = 1_500L
    }
}
