package com.ticketrush.e2e

import org.assertj.core.api.Assertions.assertThat
import java.util.UUID
import kotlin.test.Test

// E04~E06, E09, E12: 대기열 입구와 토큰 규칙
class QueueE2eTest : E2eTest() {
    @Test
    fun `E04 오픈 전 줄서기는 409 QUEUE_NOT_OPEN이다`() {
        val event = seed.notOpenedEvent()

        val response = api.register(event.eventId, nextIp())

        assertThat(response.status).`as`(response.body).isEqualTo(409)
        assertThat(response.code).isEqualTo("QUEUE_NOT_OPEN")
    }

    @Test
    fun `E05 공연이 시작한 뒤 줄서기는 409 QUEUE_CLOSED이다`() {
        val event = seed.startedEvent()

        val response = api.register(event.eventId, nextIp())

        assertThat(response.status).`as`(response.body).isEqualTo(409)
        assertThat(response.code).isEqualTo("QUEUE_CLOSED")
    }

    @Test
    fun `E06 없는 공연에 줄서기는 404 EVENT_NOT_FOUND이다`() {
        val response = api.register(NO_SUCH_EVENT, nextIp())

        assertThat(response.status).`as`(response.body).isEqualTo(404)
        assertThat(response.code).isEqualTo("EVENT_NOT_FOUND")
    }

    @Test
    fun `E09 발급한 적 없는 대기 토큰으로 순번을 조회하면 401 INVALID_QUEUE_TOKEN이다`() {
        val event = seed.openEvent()

        val response = api.poll(event.eventId, UUID.randomUUID().toString(), nextIp())

        assertThat(response.status).`as`(response.body).isEqualTo(401)
        assertThat(response.code).isEqualTo("INVALID_QUEUE_TOKEN")
    }

    @Test
    fun `E12 활성 토큰은 처음 홀드에 쓴 전화번호로만 쓸 수 있다`() {
        val event = seed.openEvent()
        val ip = nextIp()
        val token = enter(event.eventId, ip)
        val first = api.hold(event.eventId, token, listOf(event.seatIds[0]), nextPhone(), ip)
        assertThat(first.status).`as`(first.body).isEqualTo(HTTP_CREATED)

        val other = api.hold(event.eventId, token, listOf(event.seatIds[1]), nextPhone(), ip)

        assertThat(other.status).`as`(other.body).isEqualTo(403)
        assertThat(other.code).isEqualTo("QUEUE_PHONE_MISMATCH")
    }

    private companion object {
        const val NO_SUCH_EVENT = 987_654_321L
    }
}
