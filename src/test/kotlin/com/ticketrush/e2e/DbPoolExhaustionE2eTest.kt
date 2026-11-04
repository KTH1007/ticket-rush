package com.ticketrush.e2e

import org.assertj.core.api.Assertions.assertThat
import org.springframework.test.context.TestPropertySource
import java.sql.Connection
import kotlin.test.Test

// E31: DB 연결 풀이 바닥난 상황. 풀을 작게 줄인 컨텍스트에서 테스트가 연결을 모두 쥐고 요청한다.
// 다른 컨텍스트의 승격 스케줄러와 섞이지 않게 Redis DB를 따로 쓴다
@TestPropertySource(
    properties = [
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.connection-timeout=250",
        "spring.data.redis.database=3",
    ],
)
class DbPoolExhaustionE2eTest : E2eTest() {
    // 앱이 쓰는 풀과 같다(jdbc가 같은 DataSource를 쓴다)
    private val dataSource get() = requireNotNull(jdbc.dataSource)

    @Test
    fun `E31 DB 연결 풀이 바닥나면 503 TEMPORARILY_UNAVAILABLE과 Retry-After이고 풀리면 회복한다`() {
        val event = seed.openEvent()
        val held = mutableListOf<Connection>()

        val rejected =
            try {
                takeEveryConnection(held)
                api.get("/api/events/${event.eventId}")
            } finally {
                held.forEach(Connection::close)
            }
        val recovered = api.get("/api/events/${event.eventId}")

        assertThat(rejected.status).`as`(rejected.body).isEqualTo(503)
        assertThat(rejected.code).isEqualTo("TEMPORARILY_UNAVAILABLE")
        assertThat(rejected.header("Retry-After")?.toLong()).isBetween(5L, 10L)
        assertThat(recovered.status).`as`("풀린 뒤 조회: ${recovered.body}").isEqualTo(HTTP_OK)
    }

    // 스케줄러가 잠깐 쥔 연결은 곧 돌아오므로 얻을 때까지 다시 시도한다
    private fun takeEveryConnection(held: MutableList<Connection>) {
        repeat(POOL_SIZE) { index ->
            assertThat(await { runCatching { held.add(dataSource.connection) }.isSuccess })
                .`as`("연결 ${index + 1}번째를 쥔다")
                .isTrue()
        }
    }

    private companion object {
        const val POOL_SIZE = 2
    }
}
