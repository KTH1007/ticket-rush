package com.ticketrush.queue.infrastructure

import com.ticketrush.queue.QueuePolicyProperties
import com.ticketrush.queue.domain.EventSchedule
import com.ticketrush.queue.domain.RegisterResult
import com.ticketrush.support.IntegrationTest
import com.ticketrush.support.testClientIp
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Instant
import java.util.UUID
import kotlin.test.Test

// 대기열이 상한에 닿으면 새 등록을 거절한다. 판정은 등록 스크립트 안에서 하므로 거절된 요청은 순번도 IP 발급 수도 쓰지 않아야 한다
class QueueRegisterCapTest : IntegrationTest() {
    @Autowired
    lateinit var redisTemplate: StringRedisTemplate

    @Autowired
    lateinit var policy: QueuePolicyProperties

    private val cap = 3L
    private val eventId = System.nanoTime()
    private val otherEventId = eventId + 1
    private val schedule = EventSchedule(opensAt = Instant.EPOCH, startsAt = Instant.parse("9999-01-01T00:00:00Z"))

    // 다른 테스트의 승격과 섞이지 않게 예산 키를 격리하고 상한만 작게 한 어댑터
    private val repository by lazy {
        QueueRedisAdapter(redisTemplate, policy.copy(maxWaiting = cap), "queue:test-cap:${UUID.randomUUID()}:")
    }

    private fun 등록(event: Long = eventId): RegisterResult = repository.register(event, UUID.randomUUID().toString(), testClientIp, schedule)

    private fun 값(key: String): String? = redisTemplate.opsForValue().get(key)

    @Test
    fun `상한 직전까지는 등록되고 상한에 닿으면 가득 참으로 거절한다`() {
        // given
        val accepted = List(cap.toInt()) { 등록() }

        // when
        val rejected = 등록()

        // then
        assertThat(accepted).allSatisfy { assertThat(it).isInstanceOf(RegisterResult.Registered::class.java) }
        assertThat(rejected).isEqualTo(RegisterResult.Full)
    }

    @Test
    fun `거절된 요청은 순번과 IP 발급 수와 토큰 키를 쓰지 않는다`() {
        // given
        repeat(cap.toInt()) { 등록() }
        val ipKey = "queue:$eventId:register-ip:${testClientIp.value}"
        assertThat(값("queue:$eventId:seq")).isEqualTo("3")
        assertThat(값(ipKey)).isEqualTo("3")

        // when
        repeat(5) { 등록() }

        // then
        assertThat(값("queue:$eventId:seq")).isEqualTo("3")
        assertThat(값(ipKey)).isEqualTo("3")
        assertThat(redisTemplate.opsForZSet().zCard("queue:$eventId:waiting")).isEqualTo(cap)
        assertThat(redisTemplate.keys("queue:$eventId:token:*")).hasSize(cap.toInt())
    }

    @Test
    fun `승격으로 자리가 나면 다시 등록된다`() {
        // given
        repeat(cap.toInt()) { 등록() }
        assertThat(등록()).isEqualTo(RegisterResult.Full)

        // when
        repository.promote(eventId, 1)
        val afterPromotion = 등록()

        // then
        assertThat(afterPromotion).isEqualTo(RegisterResult.Registered(cap + 1))
    }

    @Test
    fun `다른 공연의 대기열 길이는 상한에 영향을 주지 않는다`() {
        // given
        repeat(cap.toInt()) { 등록(eventId) }

        // when
        val other = 등록(otherEventId)

        // then
        assertThat(other).isEqualTo(RegisterResult.Registered(1L))
    }
}
