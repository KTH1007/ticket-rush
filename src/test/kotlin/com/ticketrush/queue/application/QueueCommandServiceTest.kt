package com.ticketrush.queue.application

import com.ticketrush.queue.domain.QueueRepositoryPort
import com.ticketrush.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.Test

class QueueCommandServiceTest : IntegrationTest() {
    @Autowired
    lateinit var queueCommandService: QueueCommandService

    @Autowired
    lateinit var queueRepository: QueueRepositoryPort

    @Test
    fun `등록하면 토큰이 발급되고 그 토큰으로 순번을 조회할 수 있다`() {
        // given
        val eventId = System.nanoTime()

        // when
        val token = queueCommandService.register(eventId)

        // then
        assertThat(queueRepository.findStatus(eventId, token)).isNotNull()
    }

    @Test
    fun `등록할 때마다 서로 다른 토큰이 발급된다`() {
        // given
        val eventId = System.nanoTime()

        // when
        val token1 = queueCommandService.register(eventId)
        val token2 = queueCommandService.register(eventId)

        // then
        assertThat(token1).isNotEqualTo(token2)
    }
}
