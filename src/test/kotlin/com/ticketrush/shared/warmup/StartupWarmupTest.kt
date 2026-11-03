package com.ticketrush.shared.warmup

import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.mock.env.MockEnvironment
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test

class StartupWarmupTest {
    private val requests = CopyOnWriteArrayList<String>()
    private val queueTokens = CopyOnWriteArrayList<String>()
    private var server: HttpServer? = null

    @AfterEach
    fun stopServer() {
        server?.stop(0)
    }

    private fun start(
        eventsJson: String = """{"content":[{"id":7},{"id":9}],"page":0,"size":3,"totalElements":2,"totalPages":1}""",
        status: Int = 200,
    ): String {
        val http = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        http.createContext("/") { exchange ->
            requests += "${exchange.requestMethod} ${exchange.requestURI.path}"
            exchange.requestHeaders.getFirst("X-Queue-Token")?.let { queueTokens += it }
            val body = if (exchange.requestURI.path == "/api/events") eventsJson.toByteArray() else "{}".toByteArray()
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        http.start()
        server = http
        return "http://127.0.0.1:${http.address.port}"
    }

    private fun warmup(
        duration: Duration = Duration.ofMillis(600),
        concurrency: Int = 2,
    ) = StartupWarmup(WarmupProperties(enabled = true, duration = duration, concurrency = concurrency), MockEnvironment())

    @Test
    fun `공연 목록으로 얻은 id마다 읽기 경로를 부른다`() {
        val base = start()

        val result = warmup().warm(base)

        assertThat(result.requests).isPositive()
        assertThat(result.failures).isZero()
        assertThat(requests.toSet()).contains(
            "GET /api/events",
            "GET /api/events/7",
            "GET /api/events/7/seats/status",
            "GET /api/events/7/seats/layout",
            "GET /api/events/7/queues/me",
            "GET /api/events/9",
        )
    }

    @Test
    fun `쓰기 경로는 부르지 않는다`() {
        val base = start()

        warmup().warm(base)

        assertThat(requests).allMatch { it.startsWith("GET ") }
        assertThat(requests).noneMatch { it.contains("/hold") || it.contains("/payments") || it.contains("/cancel") }
    }

    @Test
    fun `순번 조회는 실제 대기 토큰이 아닌 값으로 부른다`() {
        val base = start()

        warmup().warm(base)

        assertThat(queueTokens).isNotEmpty().allMatch { it == "warmup" }
    }

    @Test
    fun `공연이 없으면 목록만 부른다`() {
        val base = start(eventsJson = """{"content":[],"page":0,"size":3,"totalElements":0,"totalPages":0}""")

        warmup().warm(base)

        assertThat(requests.toSet()).containsExactly("GET /api/events")
    }

    @Test
    fun `서버 오류가 나도 예외 없이 실패로 센다`() {
        val base = start(status = 500)

        val result = warmup().warm(base)

        assertThat(result.failures).isEqualTo(result.requests)
    }

    @Test
    fun `서버에 연결하지 못해도 예외를 던지지 않는다`() {
        val result = warmup(duration = Duration.ofMillis(300)).warm("http://127.0.0.1:1")

        assertThat(result.failures).isEqualTo(result.requests)
    }

    @Test
    fun `서버 포트를 모르면 아무것도 하지 않고 기동을 막지 않는다`() {
        warmup().run(DefaultApplicationArguments())

        assertThat(requests).isEmpty()
    }

    @Test
    fun `설정은 양수만 받는다`() {
        org.junit.jupiter.api
            .assertThrows<IllegalArgumentException> { WarmupProperties(duration = Duration.ZERO) }
        org.junit.jupiter.api
            .assertThrows<IllegalArgumentException> { WarmupProperties(concurrency = 0) }
    }
}
