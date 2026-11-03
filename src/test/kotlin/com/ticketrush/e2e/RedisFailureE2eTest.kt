package com.ticketrush.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import kotlin.test.Test

// E26: Redis가 메모리 한도에 닿아 쓰기를 거부하는 상황. 새 줄서기는 503으로 막고 읽기는 계속되며 풀리면 바로 회복한다
class RedisFailureE2eTest : E2eTest() {
    @AfterEach
    fun restoreRedis() {
        redisCli("CONFIG", "SET", "maxmemory", "0")
    }

    @Test
    fun `E26 Redis가 쓰기를 거부하면 새 줄서기는 503 TEMPORARILY_UNAVAILABLE이고 이미 선 사람의 조회는 되며 풀리면 회복한다`() {
        val event = seed.openEvent()
        val waitingIp = nextIp()
        val waitingToken = requireNotNull(api.register(event.eventId, waitingIp).header(E2eApi.QUEUE_TOKEN))
        redisCli("CONFIG", "SET", "maxmemory-policy", "noeviction")
        redisCli("CONFIG", "SET", "maxmemory", "1")

        val rejected = api.register(event.eventId, nextIp())
        val poll = api.poll(event.eventId, waitingToken, waitingIp)

        assertThat(rejected.status).`as`(rejected.body).isEqualTo(503)
        assertThat(rejected.code).isEqualTo("TEMPORARILY_UNAVAILABLE")
        assertThat(rejected.header("Retry-After")?.toLong()).isBetween(5L, 10L)
        assertThat(poll.status).`as`("이미 선 사람의 조회: ${poll.body}").isEqualTo(HTTP_OK)

        redisCli("CONFIG", "SET", "maxmemory", "0")
        val recovered = api.register(event.eventId, nextIp())
        assertThat(recovered.status).`as`("회복 뒤 줄서기: ${recovered.body}").isEqualTo(HTTP_CREATED)
    }
}
