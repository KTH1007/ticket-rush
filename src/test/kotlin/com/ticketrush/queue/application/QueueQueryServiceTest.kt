package com.ticketrush.queue.application

import com.ticketrush.queue.domain.InvalidQueueTokenException
import com.ticketrush.queue.domain.QueueActiveExpiredException
import com.ticketrush.queue.domain.QueueRepositoryPort
import com.ticketrush.support.IntegrationTest
import com.ticketrush.support.열린_공연에_등록
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.util.UUID
import kotlin.test.Test

class QueueQueryServiceTest : IntegrationTest() {
    @Autowired
    lateinit var queueRepository: QueueRepositoryPort

    @Autowired
    lateinit var queueQueryService: QueueQueryService

    @Autowired
    lateinit var redisTemplate: StringRedisTemplate

    @Test
    fun `등록하지 않은 토큰으로 조회하면 InvalidQueueTokenException이 발생한다`() {
        // given
        val eventId = System.nanoTime()

        // when & then
        assertThatThrownBy { queueQueryService.checkStatus(eventId, UUID.randomUUID().toString()) }
            .isInstanceOf(InvalidQueueTokenException::class.java)
    }

    @Test
    fun `승격된 사용자는 rank 0과 대기 종료를 나타내는 응답을 받는다`() {
        // given
        val eventId = System.nanoTime()
        val token = UUID.randomUUID().toString()
        queueRepository.열린_공연에_등록(eventId, token)
        queueRepository.promote(eventId, 1)

        // when
        val status = queueQueryService.checkStatus(eventId, token)

        // then
        assertThat(status.rank).isEqualTo(0)
        assertThat(status.nextPollIntervalMs).isEqualTo(0)
    }

    @Test
    fun `순위가 가까우면 짧은 폴링 간격을 받는다`() {
        // given
        val eventId = System.nanoTime()
        queueRepository.열린_공연에_등록(eventId, UUID.randomUUID().toString())
        val token = UUID.randomUUID().toString()
        queueRepository.열린_공연에_등록(eventId, token)

        // when
        val status = queueQueryService.checkStatus(eventId, token)

        // then
        assertThat(status.rank).isEqualTo(2)
        assertThat(status.nextPollIntervalMs).isEqualTo(Duration.ofSeconds(2).toMillis())
    }

    @Test
    fun `순위가 멀면 긴 폴링 간격을 받는다`() {
        // given
        val eventId = System.nanoTime()
        lateinit var lastToken: String
        repeat(101) {
            lastToken = UUID.randomUUID().toString()
            queueRepository.열린_공연에_등록(eventId, lastToken)
        }

        // when
        val status = queueQueryService.checkStatus(eventId, lastToken)

        // then
        assertThat(status.rank).isEqualTo(101)
        assertThat(status.nextPollIntervalMs).isEqualTo(Duration.ofSeconds(15).toMillis())
    }

    @Test
    fun `활성 구간이 끝난 토큰으로 조회하면 QueueActiveExpiredException이 발생한다`() {
        // given
        val eventId = System.nanoTime()
        val token = UUID.randomUUID().toString()
        queueRepository.열린_공연에_등록(eventId, token)
        queueRepository.promote(eventId, 1)
        redisTemplate.delete("queue:$eventId:active:$token")

        // when & then
        assertThatThrownBy { queueQueryService.checkStatus(eventId, token) }
            .isInstanceOf(QueueActiveExpiredException::class.java)
    }
}
