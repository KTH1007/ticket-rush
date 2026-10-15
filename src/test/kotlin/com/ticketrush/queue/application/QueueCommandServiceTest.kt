package com.ticketrush.queue.application

import com.ticketrush.event.domain.Event
import com.ticketrush.event.domain.EventRepositoryPort
import com.ticketrush.queue.domain.QueueClosedException
import com.ticketrush.queue.domain.QueueEventNotFoundException
import com.ticketrush.queue.domain.QueueNotOpenException
import com.ticketrush.queue.domain.QueueRepositoryPort
import com.ticketrush.support.IntegrationTest
import com.ticketrush.support.testClientIp
import com.ticketrush.support.공연_하나_저장
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Clock
import java.time.LocalDateTime
import kotlin.test.Test

class QueueCommandServiceTest : IntegrationTest() {
    @Autowired
    lateinit var queueCommandService: QueueCommandService

    @Autowired
    lateinit var queueRepository: QueueRepositoryPort

    @Autowired
    lateinit var eventRepository: EventRepositoryPort

    @Autowired
    lateinit var redisTemplate: StringRedisTemplate

    @Autowired
    lateinit var clock: Clock

    // 기본 공연은 일정이 고정된 과거라 시작 시각을 현재 기준으로 다시 잡아야 지금 판매 중인 공연이 된다
    private fun 판매_중인_공연_저장(): Event {
        val now = LocalDateTime.now(clock)
        return eventRepository.공연_하나_저장(opensAt = now.minusDays(1), startsAt = now.plusDays(30))
    }

    @Test
    fun `등록하면 토큰이 발급되고 그 토큰으로 순번을 조회할 수 있다`() {
        // given
        val eventId = 판매_중인_공연_저장().id

        // when
        val token = queueCommandService.register(eventId, testClientIp)

        // then
        assertThat(queueRepository.findStatus(eventId, token)).isNotNull()
    }

    // 인증 없는 등록이 없는 이벤트 번호마다 Redis 키를 남기지 않아야 한다
    @Test
    fun `존재하지 않는 이벤트에는 등록할 수 없고 대기열도 열리지 않는다`() {
        // given
        val missingEventId = Long.MAX_VALUE - System.nanoTime() % 1_000_000

        // when & then
        assertThatThrownBy {
            queueCommandService.register(
                missingEventId,
                testClientIp,
            )
        }.isInstanceOf(QueueEventNotFoundException::class.java)
        assertThat(queueRepository.activeEventIds()).doesNotContain(missingEventId)
        assertThat(redisTemplate.hasKey("queue:$missingEventId:opensAt")).isFalse()
    }

    // 티켓팅은 같은 시각에 함께 시작하므로 오픈 전에는 줄을 세우지 않는다. 막힌 등록은 대기열도 열지 않아야 한다
    @Test
    fun `오픈 전 이벤트에는 등록할 수 없고 대기열도 열리지 않는다`() {
        // given
        val now = LocalDateTime.now(clock)
        val eventId = eventRepository.공연_하나_저장(opensAt = now.plusDays(1), startsAt = now.plusDays(30)).id

        // when & then
        assertThatThrownBy { queueCommandService.register(eventId, testClientIp) }.isInstanceOf(QueueNotOpenException::class.java)
        assertThat(queueRepository.activeEventIds()).doesNotContain(eventId)
    }

    // 시작한 공연에는 새로 줄을 세우지 않는다. 이미 줄 선 사람은 건드리지 않으므로 막힌 등록만 대기열을 열지 않는다
    @Test
    fun `이미 시작한 공연에는 등록할 수 없고 대기열도 열리지 않는다`() {
        // given
        val now = LocalDateTime.now(clock)
        val eventId = eventRepository.공연_하나_저장(opensAt = now.minusDays(2), startsAt = now.minusHours(1)).id

        // when & then
        assertThatThrownBy { queueCommandService.register(eventId, testClientIp) }.isInstanceOf(QueueClosedException::class.java)
        assertThat(queueRepository.activeEventIds()).doesNotContain(eventId)
    }

    // 다음 등록부터는 DB를 보지 않고 Redis의 오픈 시각과 시작 시각으로 판정한다
    @Test
    fun `첫 등록 뒤에는 오픈 시각과 시작 시각이 Redis에 저장된다`() {
        // given
        val eventId = 판매_중인_공연_저장().id

        // when
        queueCommandService.register(eventId, testClientIp)

        // then
        assertThat(redisTemplate.hasKey("queue:$eventId:opensAt")).isTrue()
        assertThat(redisTemplate.hasKey("queue:$eventId:startsAt")).isTrue()
    }

    @Test
    fun `등록할 때마다 서로 다른 토큰이 발급된다`() {
        // given
        val eventId = 판매_중인_공연_저장().id

        // when
        val token1 = queueCommandService.register(eventId, testClientIp)
        val token2 = queueCommandService.register(eventId, testClientIp)

        // then
        assertThat(token1).isNotEqualTo(token2)
    }
}
