package com.ticketrush.e2e

import org.assertj.core.api.Assertions.assertThat
import org.springframework.test.context.TestPropertySource
import kotlin.test.Test

// E11: 활성 시간이 지나는 상황을 만들려고 활성 TTL을 1초로 줄인 컨텍스트를 쓴다.
// 다른 컨텍스트의 승격 스케줄러가 기본 TTL로 활성 키를 만들지 못하게 Redis DB를 따로 쓴다
@TestPropertySource(properties = ["ticket-rush.queue.active-ttl=1s", "spring.data.redis.database=2"])
class QueueActiveExpiryE2eTest : E2eTest() {
    @Test
    fun `E11 활성 시간이 지난 뒤 홀드하면 403 QUEUE_ACTIVE_EXPIRED이다`() {
        val event = seed.openEvent()
        val ip = nextIp()
        val token = enter(event.eventId, ip)
        Thread.sleep(ACTIVE_EXPIRY_WAIT_MILLIS)

        val response = api.hold(event.eventId, token, listOf(event.seatIds[0]), nextPhone(), ip)

        assertThat(response.status).`as`(response.body).isEqualTo(403)
        assertThat(response.code).isEqualTo("QUEUE_ACTIVE_EXPIRED")
    }

    private companion object {
        const val ACTIVE_EXPIRY_WAIT_MILLIS = 2_000L
    }
}
