package com.ticketrush.e2e

import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

// E01~E03: 줄서기부터 결제, 확정, 조회, 취소와 환불까지 한 사용자 흐름
class PurchaseJourneyE2eTest : E2eTest() {
    @Test
    fun `E01 줄서기부터 결제 확정까지 이어지고 PG에는 홀드가 정한 주문과 금액이 간다`() {
        val event = seed.openEvent()

        val purchase = purchase(event, seatCount = 2)

        assertThat(purchase.reservationNo).isNotBlank()
        assertThat(purchase.amount).isEqualTo(event.price * 2)
        toss.verify(
            1,
            postRequestedFor(urlEqualTo("/v1/payments/confirm"))
                .withRequestBody(equalToJson("""{"paymentKey":"${purchase.paymentKey}","amount":${purchase.amount}}""", true, true)),
        )
    }

    @Test
    fun `E02 확정된 예약을 예약번호와 전화번호로 조회한다`() {
        val event = seed.openEvent()
        val purchase = purchase(event, seatCount = 2)

        val lookup = api.lookup(purchase.reservationNo, purchase.phone, nextIp())

        assertThat(lookup.status).`as`(lookup.body).isEqualTo(HTTP_OK)
        val body = lookup.json()
        assertThat(body.path("status").asString(null)).isEqualTo("PAID")
        assertThat(body.path("eventId").asLong()).isEqualTo(event.eventId)
        assertThat(body.path("amount").asInt()).isEqualTo(purchase.amount)
        assertThat(body.path("seats").size()).isEqualTo(2)
    }

    @Test
    fun `E03 취소하면 환불되고 좌석이 돌아와 다른 사용자가 같은 좌석을 홀드한다`() {
        val event = seed.openEvent()
        val purchase = purchase(event)

        val canceled = api.cancel(purchase.reservationNo, purchase.phone, nextIp())

        assertThat(canceled.status).`as`(canceled.body).isEqualTo(HTTP_OK)
        assertThat(canceled.json().path("paymentStatus").asString(null)).isEqualTo("REFUNDED")
        val lookup = api.lookup(purchase.reservationNo, purchase.phone, nextIp())
        assertThat(lookup.json().path("status").asString(null)).isEqualTo("CANCELED")

        val otherIp = nextIp()
        val token = enter(event.eventId, otherIp)
        val reheld = api.hold(event.eventId, token, purchase.seatIds, nextPhone(), otherIp)
        assertThat(reheld.status).`as`("취소로 돌아온 좌석 홀드: ${reheld.body}").isEqualTo(HTTP_CREATED)
    }
}
