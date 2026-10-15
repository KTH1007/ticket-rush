package com.ticketrush.queue.infrastructure

import com.ticketrush.queue.QueuePolicyProperties
import com.ticketrush.queue.QueuePolicyProperties.RegisterLimit
import com.ticketrush.queue.domain.EventSchedule
import com.ticketrush.queue.domain.RegisterResult
import com.ticketrush.shared.ClientIp
import com.ticketrush.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test

// 테스트 설정은 IP 한도를 크게 올려 두므로, 작은 한도의 어댑터를 따로 만들고 테스트마다 다른 IP를 쓴다
class QueueRegisterIpLimitTest : IntegrationTest() {
    @Autowired
    lateinit var redisTemplate: StringRedisTemplate

    @Autowired
    lateinit var policy: QueuePolicyProperties

    private val limit = 3
    private val window = Duration.ofMinutes(1)
    private val openSchedule = EventSchedule(opensAt = Instant.EPOCH, startsAt = Instant.parse("9999-01-01T00:00:00Z"))
    private val eventId = System.nanoTime()
    private val clientIp = ClientIp("ip-${UUID.randomUUID()}")
    private lateinit var repository: QueueRedisAdapter

    @BeforeEach
    fun setUp() {
        repository = QueueRedisAdapter(redisTemplate, policy.copy(registerLimit = RegisterLimit(maxPerIp = limit, window = window)))
    }

    private fun 등록(
        eventId: Long = this.eventId,
        ip: ClientIp = clientIp,
        schedule: EventSchedule = openSchedule,
    ): RegisterResult = repository.register(eventId, UUID.randomUUID().toString(), ip, schedule)

    private fun counterKey(
        eventId: Long = this.eventId,
        ip: ClientIp = clientIp,
    ) = "queue:$eventId:register-ip:${ip.value}"

    @Test
    fun `상한까지는 등록되고 다음부터는 RateLimited다`() {
        // given
        val results = (1..limit).map { 등록() }

        // when
        val limited = 등록()

        // then
        assertThat(results).allMatch { it is RegisterResult.Registered }
        assertThat(limited).isInstanceOf(RegisterResult.RateLimited::class.java)
        val retryAfter = (limited as RegisterResult.RateLimited).retryAfter
        assertThat(retryAfter).isPositive().isLessThanOrEqualTo(window)
    }

    @Test
    fun `다른 IP는 영향받지 않는다`() {
        // given
        repeat(limit + 1) { 등록() }

        // when
        val other = 등록(ip = ClientIp("ip-${UUID.randomUUID()}"))

        // then
        assertThat(other).isInstanceOf(RegisterResult.Registered::class.java)
    }

    @Test
    fun `다른 공연은 한도를 따로 센다`() {
        // given
        repeat(limit + 1) { 등록() }

        // when
        val otherEvent = 등록(eventId = System.nanoTime())

        // then
        assertThat(otherEvent).isInstanceOf(RegisterResult.Registered::class.java)
    }

    @Test
    fun `거절돼도 순번을 쓰지 않는다`() {
        // given
        repeat(limit) { 등록() }
        등록()

        // when
        val next = 등록(ip = ClientIp("ip-${UUID.randomUUID()}"))

        // then
        assertThat(next).isEqualTo(RegisterResult.Registered(limit + 1L))
    }

    @Test
    fun `오픈 전 거절은 한도를 깎지 않는다`() {
        // given
        val notOpen = EventSchedule(opensAt = Instant.parse("9999-01-01T00:00:00Z"), startsAt = Instant.parse("9999-02-01T00:00:00Z"))

        // when
        val results = (1..limit + 2).map { 등록(schedule = notOpen) }

        // then
        assertThat(results).allMatch { it is RegisterResult.NotOpen }
        assertThat(redisTemplate.hasKey(counterKey())).isFalse()
    }

    @Test
    fun `시작 뒤 거절은 한도를 깎지 않는다`() {
        // given
        val closed = EventSchedule(opensAt = Instant.EPOCH, startsAt = Instant.parse("2020-01-01T00:00:00Z"))

        // when
        val results = (1..limit + 2).map { 등록(schedule = closed) }

        // then
        assertThat(results).allMatch { it is RegisterResult.Closed }
        assertThat(redisTemplate.hasKey(counterKey())).isFalse()
    }

    @Test
    fun `발급 수 키에는 윈도우 TTL이 걸리고 이후 발급이 윈도우를 늘리지 않는다`() {
        // given
        등록()
        val ttlAfterFirst = redisTemplate.getExpire(counterKey())

        // when
        repeat(limit - 1) { 등록() }
        val ttlAfterLast = redisTemplate.getExpire(counterKey())

        // then
        assertThat(ttlAfterFirst).isBetween(1L, window.seconds)
        assertThat(ttlAfterLast).isLessThanOrEqualTo(ttlAfterFirst)
    }

    @Test
    fun `윈도우가 지나 키가 사라지면 다시 등록된다`() {
        // given
        repeat(limit) { 등록() }
        assertThat(등록()).isInstanceOf(RegisterResult.RateLimited::class.java)

        // when
        redisTemplate.delete(counterKey())

        // then
        assertThat(등록()).isInstanceOf(RegisterResult.Registered::class.java)
    }
}
