package com.ticketrush.e2e

import org.assertj.core.api.Assertions.assertThat
import org.springframework.test.context.TestPropertySource
import kotlin.test.Test

// E07, E08, E10: 한도와 승격 전 상태를 만들려고 설정이 다른 컨텍스트를 쓴다.
// 승격 스케줄러는 사실상 끄고(주기 1시간), 대기열 상한은 3, IP별 발급 한도는 2로 둔다.
// 다른 컨텍스트의 승격 스케줄러(200ms)가 같은 Redis의 대기열을 승격시키지 못하게 Redis DB를 따로 쓴다
@TestPropertySource(
    properties = [
        "spring.data.redis.database=1",
        "ticket-rush.queue.promotion-interval=1h",
        "ticket-rush.queue.max-waiting=3",
        "ticket-rush.queue.register-limit.max-per-ip=2",
    ],
)
class QueueLimitsE2eTest : E2eTest() {
    @Test
    fun `E07 대기열이 상한에 닿으면 503 QUEUE_FULL과 Retry-After를 주고 이미 선 사람은 그대로다`() {
        val event = seed.openEvent()
        val tokens =
            (1..MAX_WAITING).map {
                val ip = nextIp()
                val registered = api.register(event.eventId, ip)
                assertThat(registered.status).`as`("상한 안의 줄서기: ${registered.body}").isEqualTo(HTTP_CREATED)
                ip to requireNotNull(registered.header(E2eApi.QUEUE_TOKEN))
            }

        val full = api.register(event.eventId, nextIp())

        assertThat(full.status).`as`(full.body).isEqualTo(503)
        assertThat(full.code).isEqualTo("QUEUE_FULL")
        assertThat(full.header("Retry-After")?.toLong()).isBetween(5L, 10L)
        assertThat(full.header("Cache-Control")).isEqualTo("max-age=1")
        assertThat(full.json().has("traceId")).`as`("재사용되는 응답에는 요청별 traceId가 없다").isFalse()
        tokens.forEach { (ip, token) ->
            val poll = api.poll(event.eventId, token, ip)
            assertThat(poll.status).`as`("이미 선 사람의 조회: ${poll.body}").isEqualTo(HTTP_OK)
        }
    }

    @Test
    fun `E08 같은 IP가 한도를 넘겨 줄서기하면 429 QUEUE_REGISTER_RATE_LIMITED이다`() {
        val event = seed.openEvent()
        val ip = nextIp()
        repeat(IP_LIMIT) {
            assertThat(api.register(event.eventId, ip).status).isEqualTo(HTTP_CREATED)
        }

        val limited = api.register(event.eventId, ip)

        assertThat(limited.status).`as`(limited.body).isEqualTo(429)
        assertThat(limited.code).isEqualTo("QUEUE_REGISTER_RATE_LIMITED")
        assertThat(limited.header("Retry-After")).isNotNull()
        assertThat(limited.header("Cache-Control")).isNull()
    }

    @Test
    fun `E10 승격되기 전에 좌석을 홀드하면 403 QUEUE_NOT_ACTIVE이다`() {
        val event = seed.openEvent()
        val ip = nextIp()
        val registered = api.register(event.eventId, ip)
        val token = requireNotNull(registered.header(E2eApi.QUEUE_TOKEN))

        val response = api.hold(event.eventId, token, listOf(event.seatIds[0]), nextPhone(), ip)

        assertThat(response.status).`as`(response.body).isEqualTo(403)
        assertThat(response.code).isEqualTo("QUEUE_NOT_ACTIVE")
    }

    private companion object {
        const val MAX_WAITING = 3
        const val IP_LIMIT = 2
    }
}
