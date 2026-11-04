package com.ticketrush.e2e

import org.assertj.core.api.Assertions.assertThat
import java.time.Duration
import kotlin.test.Test

// E13~E16: 좌석 홀드
class HoldE2eTest : E2eTest() {
    @Test
    fun `E13 여러 사용자가 같은 좌석을 동시에 홀드하면 한 명만 201이고 나머지는 409 SEAT_ALREADY_HELD이다`() {
        val event = seed.openEvent()
        val users =
            (1..CONTENDERS).map {
                val ip = nextIp()
                Triple(ip, nextPhone(), enter(event.eventId, ip))
            }

        val results =
            concurrently(CONTENDERS) { index ->
                val (ip, phone, token) = users[index]
                api.hold(event.eventId, token, listOf(event.seatIds[0]), phone, ip)
            }

        assertThat(results.count { it.status == HTTP_CREATED }).`as`(results.joinToString { "${it.status}" }).isEqualTo(1)
        results.filter { it.status != HTTP_CREATED }.forEach {
            assertThat(it.status).`as`(it.body).isEqualTo(409)
            assertThat(it.code).isEqualTo("SEAT_ALREADY_HELD")
        }
    }

    @Test
    fun `E14 한 전화번호가 두 장을 넘겨 홀드하면 409 RESERVATION_LIMIT_EXCEEDED이다`() {
        val event = seed.openEvent()
        val ip = nextIp()
        val phone = nextPhone()
        val token = enter(event.eventId, ip)
        val two = api.hold(event.eventId, token, event.seatIds.take(2), phone, ip)
        assertThat(two.status).`as`(two.body).isEqualTo(HTTP_CREATED)

        val third = api.hold(event.eventId, token, listOf(event.seatIds[2]), phone, ip)

        assertThat(third.status).`as`(third.body).isEqualTo(409)
        assertThat(third.code).isEqualTo("RESERVATION_LIMIT_EXCEEDED")
    }

    @Test
    fun `E15 없는 좌석은 404 SEAT_NOT_FOUND이고 좌석이 없거나 너무 많은 선택은 400이다`() {
        val event = seed.openEvent()
        val ip = nextIp()
        val token = enter(event.eventId, ip)

        val missing = api.hold(event.eventId, token, listOf(NO_SUCH_SEAT), nextPhone(), ip)
        val empty = api.hold(event.eventId, token, emptyList(), nextPhone(), ip)
        val tooMany = api.hold(event.eventId, token, event.seatIds.take(3), nextPhone(), ip)

        assertThat(missing.status).`as`(missing.body).isEqualTo(404)
        assertThat(missing.code).isEqualTo("SEAT_NOT_FOUND")
        assertThat(empty.status).`as`(empty.body).isEqualTo(400)
        assertThat(tooMany.status).`as`(tooMany.body).isEqualTo(400)
    }

    @Test
    fun `E16 홀드가 만료되면 좌석이 풀려 다른 사용자가 홀드할 수 있다`() {
        val event = seed.openEvent()
        val reservationId = holdSeat(event).hold.path("reservationId").asLong()
        val otherIp = nextIp()
        val otherPhone = nextPhone()
        val otherToken = enter(event.eventId, otherIp)
        val tooEarly = api.hold(event.eventId, otherToken, listOf(event.seatIds[0]), otherPhone, otherIp)
        assertThat(tooEarly.status).`as`("홀드 중인 좌석: ${tooEarly.body}").isEqualTo(409)

        var retried = tooEarly
        val released =
            await(Duration.ofSeconds(HOLD_EXPIRY_WAIT_SECONDS)) {
                retried = api.hold(event.eventId, otherToken, listOf(event.seatIds[0]), otherPhone, otherIp)
                retried.status == HTTP_CREATED
            }

        assertThat(released).`as`("만료 뒤 홀드: ${retried.body}").isTrue()
        assertThat(reservationStatus(reservationId)).isEqualTo("EXPIRED")
    }

    private companion object {
        const val CONTENDERS = 6
        const val NO_SUCH_SEAT = 987_654_321L
        const val HOLD_EXPIRY_WAIT_SECONDS = 25L
    }
}
