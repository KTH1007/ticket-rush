package com.ticketrush.e2e

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.redis.testcontainers.RedisContainer
import com.ticketrush.event.domain.EventRepositoryPort
import com.ticketrush.reservation.domain.GradeRepositoryPort
import com.ticketrush.reservation.domain.SeatRepositoryPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// 앱 전체를 실제 HTTP로 거치는 테스트의 기반. 실제 Postgres, Redis와 Toss 대역(WireMock)을 쓰고 스케줄러도 실제로 돈다.
// 테스트마다 새 공연과 새 사용자 IP를 써서 서로의 상태(대기열, 좌석, IP 한도)에 끼지 않게 한다
@Tag("e2e")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test", "e2e")
@AutoConfigureMetrics
// 주입받는 lateinit 필드는 val로 못 바꾸고, 상속해서 쓰려고 만든 클래스라 object나 interface 제안은 오탐이다
@Suppress("UtilityClassWithPublicConstructor", "AbstractClassCanBeInterface", "AbstractClassCanBeConcreteClass", "VarCouldBeVal")
abstract class E2eTest {
    @Value("\${local.server.port}")
    private var port: Int = 0

    @Autowired
    private lateinit var events: EventRepositoryPort

    @Autowired
    private lateinit var grades: GradeRepositoryPort

    @Autowired
    private lateinit var seats: SeatRepositoryPort

    @Autowired
    protected lateinit var jdbc: JdbcTemplate

    protected val api: E2eApi by lazy { E2eApi("http://127.0.0.1:$port") }
    protected val seed: E2eSeed by lazy { E2eSeed(events, grades, seats) }
    protected val toss: WireMockServer get() = tossServer

    @BeforeEach
    fun resetToss() {
        tossServer.resetAll()
        stubTossApproval()
        stubTossCancel()
    }

    // 요청에 실린 paymentKey, orderId, amount를 그대로 되돌려 승인한다
    protected fun stubTossApproval() {
        tossServer.stubFor(
            post(urlEqualTo("/v1/payments/confirm")).willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(APPROVAL_BODY),
            ),
        )
    }

    protected fun stubTossCancel() {
        tossServer.stubFor(
            post(urlPathMatching("/v1/payments/[^/]+/cancel")).willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("""{"paymentKey":"{{request.path.[2]}}","status":"CANCELED"}"""),
            ),
        )
    }

    // 테스트마다 다른 사용자 IP(문서용 대역)와 전화번호
    protected fun nextIp(): String = "198.51.100.${ipCounter.incrementAndGet() % IP_RANGE + 1}"

    protected fun nextPhone(): String = "010" + (10_000_000 + phoneCounter.incrementAndGet()).toString()

    protected fun nextPaymentKey(): String = "pk-e2e-${UUID.randomUUID()}"

    // 조건이 참이 될 때까지 기다린다. 제한 시간 안에 안 되면 false
    protected fun await(
        timeout: Duration = Duration.ofSeconds(10),
        condition: () -> Boolean,
    ): Boolean {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(POLL_MILLIS)
        }
        return condition()
    }

    protected fun reservationStatus(reservationId: Long): String? =
        jdbc.queryForList("SELECT status FROM reservation WHERE id = ?", String::class.java, reservationId).firstOrNull()

    protected fun paymentStatusOf(reservationId: Long): String? =
        jdbc.queryForList("SELECT status FROM payment WHERE reservation_id = ?", String::class.java, reservationId).firstOrNull()

    data class Held(
        val ip: String,
        val phone: String,
        val token: String,
        val hold: tools.jackson.databind.JsonNode,
    )

    // 줄서기, 승격 대기, 좌석 홀드까지 하고 결제 전 상태로 둔다
    protected fun holdSeats(
        event: E2eSeed.Seeded,
        seatIndexes: List<Int>,
    ): Held {
        val ip = nextIp()
        val phone = nextPhone()
        val token = enter(event.eventId, ip)
        val held = api.hold(event.eventId, token, seatIndexes.map { event.seatIds[it] }, phone, ip)
        assertThat(held.status).`as`("홀드: ${held.body}").isEqualTo(HTTP_CREATED)
        return Held(ip, phone, token, held.json())
    }

    protected fun holdSeat(
        event: E2eSeed.Seeded,
        seatIndex: Int = 0,
    ): Held = holdSeats(event, listOf(seatIndex))

    // 같은 일을 여러 스레드에서 동시에 시작해 결과를 모은다
    protected fun <T> concurrently(
        count: Int,
        action: (Int) -> T,
    ): List<T> {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(count)
        try {
            val futures =
                (0 until count).map { index ->
                    pool.submit<T> {
                        start.await()
                        action(index)
                    }
                }
            start.countDown()
            return futures.map { it.get(CONCURRENT_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    protected fun redisCli(vararg args: String): String {
        val result = redis.execInContainer("redis-cli", *args)
        check(result.exitCode == 0) { "redis-cli ${args.toList()} 실패: ${result.stderr}" }
        return result.stdout.trim()
    }

    // 줄서기를 하고 승격될 때까지 기다려 활성 대기 토큰을 돌려준다
    protected fun enter(
        eventId: Long,
        ip: String,
    ): String {
        val registered = api.register(eventId, ip)
        assertThat(registered.status).`as`("줄서기: ${registered.body}").isEqualTo(HTTP_CREATED)
        val token = requireNotNull(registered.header(E2eApi.QUEUE_TOKEN)) { "대기 토큰 헤더가 없다" }
        val active = api.awaitActive(eventId, token, ip)
        assertThat(active.status).`as`("승격 대기: ${active.body}").isEqualTo(HTTP_OK)
        assertThat(active.json().path("rank").asLong(-1)).`as`("승격 대기: ${active.body}").isEqualTo(0L)
        return token
    }

    data class Purchase(
        val eventId: Long,
        val reservationId: Long,
        val seatIds: List<Long>,
        val reservationNo: String,
        val phone: String,
        val ip: String,
        val amount: Int,
        val paymentKey: String,
    )

    // 줄서기부터 결제 확정까지 한 번 돌려 확정된 예약을 만든다
    protected fun purchase(
        event: E2eSeed.Seeded,
        seatCount: Int = 1,
    ): Purchase {
        val held = holdSeats(event, (0 until seatCount).toList())
        val paymentKey = nextPaymentKey()
        val paid = api.pay(held.hold, paymentKey, held.ip)
        assertThat(paid.status).`as`("결제: ${paid.body}").isEqualTo(HTTP_CREATED)
        return Purchase(
            eventId = event.eventId,
            reservationId = held.hold.path("reservationId").asLong(),
            seatIds = event.seatIds.take(seatCount),
            reservationNo = paid.json().path("reservationNo").asString(null),
            phone = held.phone,
            ip = held.ip,
            amount = held.hold.path("amount").asInt(),
            paymentKey = paymentKey,
        )
    }

    companion object {
        private val ipCounter = AtomicInteger()
        private val phoneCounter = AtomicInteger()
        private const val IP_RANGE = 250
        const val HTTP_OK = 200
        private const val POLL_MILLIS = 100L
        private const val CONCURRENT_TIMEOUT_SECONDS = 30L
        const val HTTP_CREATED = 201

        // 요청에 실린 paymentKey, orderId, amount를 WireMock 템플릿으로 그대로 되돌린다
        private val APPROVAL_BODY =
            listOf(
                """{"paymentKey":"{{jsonPath request.body '${'$'}.paymentKey'}}",""",
                """"orderId":"{{jsonPath request.body '${'$'}.orderId'}}",""",
                """"status":"DONE","totalAmount":{{jsonPath request.body '${'$'}.amount'}},""",
                """"approvedAt":"2026-11-03T10:00:00+09:00"}""",
            ).joinToString("")

        private val postgres: PostgreSQLContainer =
            PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
                .withDatabaseName("ticketrush")
                .withUsername("test")
                .withPassword("test")
                .withReuse(true)
                .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=300")
                .apply { start() }

        val redis: RedisContainer =
            RedisContainer(DockerImageName.parse("redis:8-alpine"))
                .withReuse(true)
                .apply { start() }

        // 와일드카드가 아닌 루프백에만 바인드한다(다른 로컬 서버가 같은 번호를 쥐고 있어도 요청을 가로채지 못하게)
        private val tossServer: WireMockServer =
            WireMockServer(wireMockConfig().dynamicPort().bindAddress("127.0.0.1").globalTemplating(true)).apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.data.redis.host", redis::getRedisHost)
            registry.add("spring.data.redis.port") { redis.redisPort }
            registry.add("ticket-rush.toss.base-url") { "http://127.0.0.1:${tossServer.port()}" }
        }
    }
}
