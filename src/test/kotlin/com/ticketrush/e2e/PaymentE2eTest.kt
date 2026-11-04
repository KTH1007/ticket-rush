package com.ticketrush.e2e

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.matching
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import org.assertj.core.api.Assertions.assertThat
import java.time.Duration
import java.util.UUID
import kotlin.test.Test

// E17~E20, E22, E25: 결제 승인과 환불의 실패 경로
class PaymentE2eTest : E2eTest() {
    @Test
    fun `E17 결제가 거절되면 409 PAYMENT_DECLINED이고 같은 예약으로 다시 시도해 확정한다`() {
        val event = seed.openEvent()
        val held = holdSeat(event)
        toss.stubFor(
            post(urlEqualTo("/v1/payments/confirm")).atPriority(1).willReturn(
                aResponse()
                    .withStatus(400)
                    .withHeader("Content-Type", "application/json")
                    .withBody("""{"code":"REJECT_CARD_COMPANY","message":"카드사에서 거절했습니다"}"""),
            ),
        )

        val declined = api.pay(held.hold, nextPaymentKey(), held.ip)

        assertThat(declined.status).`as`(declined.body).isEqualTo(409)
        assertThat(declined.code).isEqualTo("PAYMENT_DECLINED")
        stubTossApproval()
        toss.removeStubMapping(toss.listAllStubMappings().mappings.first { it.priority == 1 })
        val retried = api.pay(held.hold, nextPaymentKey(), held.ip)
        assertThat(retried.status).`as`("거절 뒤 재시도: ${retried.body}").isEqualTo(HTTP_CREATED)
        assertThat(reservationStatus(held.hold.path("reservationId").asLong())).isEqualTo("PAID")
    }

    @Test
    fun `E18 같은 결제 요청을 동시에 두 번 보내도 PG 승인은 한 번이고 예약은 한 번만 확정된다`() {
        val event = seed.openEvent()
        val held = holdSeat(event)
        val paymentKey = nextPaymentKey()

        val results = concurrently(2) { api.pay(held.hold, paymentKey, held.ip) }

        assertThat(
            results.count { it.status == HTTP_CREATED },
        ).`as`(results.joinToString { "${it.status} ${it.code}" }).isGreaterThanOrEqualTo(1)
        results.filter { it.status != HTTP_CREATED }.forEach { assertThat(it.status).`as`(it.body).isEqualTo(409) }
        toss.verify(1, postRequestedFor(urlEqualTo("/v1/payments/confirm")))
        assertThat(reservationStatus(held.hold.path("reservationId").asLong())).isEqualTo("PAID")
    }

    @Test
    fun `E19 홀드 만료가 임박하면 결제를 시작할 수 없고 409 RESERVATION_HOLD_EXPIRING이다`() {
        val event = seed.openEvent()
        val held = holdSeat(event)
        Thread.sleep(HOLD_EXPIRING_WAIT_MILLIS)

        val response = api.pay(held.hold, nextPaymentKey(), held.ip)

        assertThat(response.status).`as`(response.body).isEqualTo(409)
        assertThat(response.code).isEqualTo("RESERVATION_HOLD_EXPIRING")
        toss.verify(0, postRequestedFor(urlEqualTo("/v1/payments/confirm")))
    }

    @Test
    fun `E20 홀드 토큰이 다르면 401 HOLD_TOKEN_MISMATCH이고 PG를 부르지 않는다`() {
        val event = seed.openEvent()
        val held = holdSeat(event)

        val response = api.pay(held.hold, nextPaymentKey(), held.ip, holdToken = UUID.randomUUID().toString())

        assertThat(response.status).`as`(response.body).isEqualTo(401)
        assertThat(response.code).isEqualTo("HOLD_TOKEN_MISMATCH")
        toss.verify(0, postRequestedFor(urlEqualTo("/v1/payments/confirm")))
    }

    @Test
    fun `E22 PG 응답이 없으면 503 뒤 PENDING이 남고 회수 스케줄러가 같은 멱등키로 다시 승인해 확정한다`() {
        val event = seed.openEvent()
        val held = holdSeat(event)
        val reservationId = held.hold.path("reservationId").asLong()
        toss.stubFor(
            post(urlEqualTo("/v1/payments/confirm")).atPriority(1).willReturn(aResponse().withStatus(200).withFixedDelay(PG_HANG_MILLIS)),
        )

        val timedOut = api.pay(held.hold, nextPaymentKey(), held.ip)

        assertThat(timedOut.status).`as`(timedOut.body).isEqualTo(503)
        assertThat(timedOut.code).isEqualTo("PAYMENT_GATEWAY_UNAVAILABLE")
        assertThat(paymentStatusOf(reservationId)).isEqualTo("PENDING")
        toss.removeStubMapping(toss.listAllStubMappings().mappings.first { it.priority == 1 })

        val reclaimed = await(Duration.ofSeconds(RECLAIM_WAIT_SECONDS)) { reservationStatus(reservationId) == "PAID" }

        assertThat(reclaimed).`as`("회수 뒤 예약 상태: ${reservationStatus(reservationId)}, 결제: ${paymentStatusOf(reservationId)}").isTrue()
        val keys = toss.findAll(postRequestedFor(urlEqualTo("/v1/payments/confirm"))).map { it.getHeader("Idempotency-Key") }
        assertThat(keys.size).isGreaterThanOrEqualTo(2)
        assertThat(keys.toSet()).`as`("재시도는 같은 멱등키를 쓴다").hasSize(1)
    }

    @Test
    fun `E25 환불이 계속 실패하면 정해진 횟수만 재시도하고 멈춘 채 CANCELED로 남는다`() {
        val event = seed.openEvent()
        val purchase = purchase(event)
        toss.stubFor(post(urlPathMatching("/v1/payments/[^/]+/cancel")).atPriority(1).willReturn(aResponse().withStatus(500)))

        val canceled = api.cancel(purchase.reservationNo, purchase.phone, nextIp())

        assertThat(canceled.status).`as`(canceled.body).isEqualTo(HTTP_OK)
        assertThat(canceled.json().path("paymentStatus").asString(null)).isEqualTo("CANCELED")
        val cancelCalls = postRequestedFor(urlPathMatching("/v1/payments/[^/]+/cancel")).withHeader("Idempotency-Key", matching(".+"))
        val exhausted = await(Duration.ofSeconds(RETRY_WAIT_SECONDS)) { toss.findAll(cancelCalls).size >= REFUND_MAX_ATTEMPTS }
        assertThat(exhausted).`as`("환불 시도 횟수: ${toss.findAll(cancelCalls).size}").isTrue()
        Thread.sleep(QUIET_MILLIS)
        assertThat(toss.findAll(cancelCalls)).`as`("한도 뒤에는 더 시도하지 않는다").hasSize(REFUND_MAX_ATTEMPTS)
        assertThat(paymentStatusOf(purchase.reservationId)).isEqualTo("CANCELED")
    }

    private companion object {
        const val HOLD_EXPIRING_WAIT_MILLIS = 8_000L
        const val PG_HANG_MILLIS = 3_000
        const val RECLAIM_WAIT_SECONDS = 20L
        const val RETRY_WAIT_SECONDS = 20L
        const val REFUND_MAX_ATTEMPTS = 5
        const val QUIET_MILLIS = 3_000L
    }
}
