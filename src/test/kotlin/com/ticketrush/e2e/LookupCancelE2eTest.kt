package com.ticketrush.e2e

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

// E23, E24: 예약 조회와 취소의 보호 규칙
class LookupCancelE2eTest : E2eTest() {
    @Test
    fun `E23 틀린 전화번호로 조회를 반복하면 404 RESERVATION_LOOKUP_FAILED 뒤 한도를 넘어 429이다`() {
        val purchase = purchase(seed.openEvent())
        val ip = nextIp()
        val wrongPhone = nextPhone()

        val failures = (1..LOOKUP_MAX_ATTEMPTS).map { api.lookup(purchase.reservationNo, wrongPhone, ip) }
        val blocked = api.lookup(purchase.reservationNo, wrongPhone, ip)

        failures.forEach {
            assertThat(it.status).`as`(it.body).isEqualTo(404)
            assertThat(it.code).isEqualTo("RESERVATION_LOOKUP_FAILED")
        }
        assertThat(blocked.status).`as`(blocked.body).isEqualTo(429)
        assertThat(blocked.code).isEqualTo("RESERVATION_LOOKUP_RATE_LIMITED")
        assertThat(blocked.header("Retry-After")).isNotNull()
    }

    @Test
    fun `E24 이미 취소된 예약을 다시 취소하면 409 RESERVATION_ALREADY_CANCELED이다`() {
        val purchase = purchase(seed.openEvent())
        val first = api.cancel(purchase.reservationNo, purchase.phone, nextIp())
        assertThat(first.status).`as`(first.body).isEqualTo(HTTP_OK)

        val second = api.cancel(purchase.reservationNo, purchase.phone, nextIp())

        assertThat(second.status).`as`(second.body).isEqualTo(409)
        assertThat(second.code).isEqualTo("RESERVATION_ALREADY_CANCELED")
    }

    private companion object {
        const val LOOKUP_MAX_ATTEMPTS = 5
    }
}
