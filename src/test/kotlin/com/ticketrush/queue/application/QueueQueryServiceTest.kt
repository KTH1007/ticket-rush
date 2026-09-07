package com.ticketrush.queue.application

import com.ticketrush.queue.domain.InvalidQueueTokenException
import com.ticketrush.queue.domain.QueueRepositoryPort
import com.ticketrush.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.util.UUID
import kotlin.test.Test

class QueueQueryServiceTest : IntegrationTest() {
    @Autowired
    lateinit var queueRepository: QueueRepositoryPort

    @Autowired
    lateinit var queueQueryService: QueueQueryService

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
        queueRepository.register(eventId, token)
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
        queueRepository.register(eventId, UUID.randomUUID().toString())
        val token = UUID.randomUUID().toString()
        queueRepository.register(eventId, token)

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
            queueRepository.register(eventId, lastToken)
        }

        // when
        val status = queueQueryService.checkStatus(eventId, lastToken)

        // then
        assertThat(status.rank).isEqualTo(101)
        assertThat(status.nextPollIntervalMs).isEqualTo(Duration.ofSeconds(15).toMillis())
    }
}
