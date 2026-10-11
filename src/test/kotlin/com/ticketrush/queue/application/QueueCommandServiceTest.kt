package com.ticketrush.queue.application

import com.ticketrush.event.domain.EventRepositoryPort
import com.ticketrush.queue.domain.QueueEventNotFoundException
import com.ticketrush.queue.domain.QueueNotOpenException
import com.ticketrush.queue.domain.QueueRepositoryPort
import com.ticketrush.support.IntegrationTest
import com.ticketrush.support.공연_하나_저장
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.springframework.beans.factory.annotation.Autowired
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
    lateinit var clock: Clock

    @Test
    fun `등록하면 토큰이 발급되고 그 토큰으로 순번을 조회할 수 있다`() {
        // given
        val eventId = eventRepository.공연_하나_저장().id

        // when
        val token = queueCommandService.register(eventId)

        // then
        assertThat(queueRepository.findStatus(eventId, token)).isNotNull()
    }

    // 인증 없는 등록이 없는 이벤트 번호마다 Redis 키를 남기지 않아야 한다
    @Test
    fun `존재하지 않는 이벤트에는 등록할 수 없고 대기열도 열리지 않는다`() {
        // given
        val missingEventId = Long.MAX_VALUE - System.nanoTime() % 1_000_000

        // when & then
        assertThatThrownBy { queueCommandService.register(missingEventId) }.isInstanceOf(QueueEventNotFoundException::class.java)
        assertThat(queueRepository.isActive(missingEventId)).isFalse()
    }

    // 티켓팅은 같은 시각에 함께 시작하므로 오픈 전에는 줄을 세우지 않는다. 막힌 등록은 Redis 키도 남기지 않아야 한다
    @Test
    fun `오픈 전 이벤트에는 등록할 수 없고 대기열도 열리지 않는다`() {
        // given
        val eventId = eventRepository.공연_하나_저장(opensAt = LocalDateTime.now(clock).plusDays(1)).id

        // when & then
        assertThatThrownBy { queueCommandService.register(eventId) }.isInstanceOf(QueueNotOpenException::class.java)
        assertThat(queueRepository.isActive(eventId)).isFalse()
    }

    @Test
    fun `등록할 때마다 서로 다른 토큰이 발급된다`() {
        // given
        val eventId = eventRepository.공연_하나_저장().id

        // when
        val token1 = queueCommandService.register(eventId)
        val token2 = queueCommandService.register(eventId)

        // then
        assertThat(token1).isNotEqualTo(token2)
    }
}
