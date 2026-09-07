package com.ticketrush.queue.domain

import com.ticketrush.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID
import kotlin.test.Test

class QueueRepositoryTest : IntegrationTest() {
    @Autowired
    lateinit var queueRepository: QueueRepositoryPort

    @Test
    fun `등록하면 이전 순번보다 1 큰 순번을 받는다`() {
        // given
        val eventId = System.nanoTime()

        // when
        val first = queueRepository.register(eventId, UUID.randomUUID().toString())
        val second = queueRepository.register(eventId, UUID.randomUUID().toString())

        // then
        assertThat(second).isEqualTo(first + 1)
    }

    @Test
    fun `이벤트가 다르면 순번 증가가 서로 영향을 주지 않는다`() {
        // given
        val eventA = System.nanoTime()
        val eventB = System.nanoTime() + 1

        // when
        val a1 = queueRepository.register(eventA, UUID.randomUUID().toString())
        queueRepository.register(eventB, UUID.randomUUID().toString())
        val a2 = queueRepository.register(eventA, UUID.randomUUID().toString())

        // then
        assertThat(a2).isEqualTo(a1 + 1)
    }

    @Test
    fun `승격하면 순번이 빠른 사람부터 count만큼 꺼내진다`() {
        // given
        val eventId = System.nanoTime()
        val token1 = UUID.randomUUID().toString()
        val token2 = UUID.randomUUID().toString()
        val token3 = UUID.randomUUID().toString()
        queueRepository.register(eventId, token1)
        queueRepository.register(eventId, token2)
        queueRepository.register(eventId, token3)

        // when
        val promoted = queueRepository.promote(eventId, 2)

        // then
        assertThat(promoted).containsExactly(token1, token2)
    }

    @Test
    fun `대기 인원보다 많은 count를 요청해도 있는 만큼만 승격된다`() {
        // given
        val eventId = System.nanoTime()
        val token = UUID.randomUUID().toString()
        queueRepository.register(eventId, token)

        // when
        val promoted = queueRepository.promote(eventId, 10)

        // then
        assertThat(promoted).containsExactly(token)
    }

    @Test
    fun `승격된 사람은 다시 승격 대상에 포함되지 않는다`() {
        // given
        val eventId = System.nanoTime()
        val token1 = UUID.randomUUID().toString()
        val token2 = UUID.randomUUID().toString()
        queueRepository.register(eventId, token1)
        queueRepository.register(eventId, token2)
        queueRepository.promote(eventId, 1)

        // when
        val secondPromotion = queueRepository.promote(eventId, 1)

        // then
        assertThat(secondPromotion).containsExactly(token2)
    }

    @Test
    fun `등록한 토큰의 순번을 조회할 수 있다`() {
        // given
        val eventId = System.nanoTime()
        val token = UUID.randomUUID().toString()

        // when
        val seq = queueRepository.register(eventId, token)

        // then
        assertThat(queueRepository.findSequence(eventId, token)).isEqualTo(seq)
    }

    @Test
    fun `등록하지 않은 토큰은 순번 조회 시 null을 반환한다`() {
        // given
        val eventId = System.nanoTime()

        // when & then
        assertThat(queueRepository.findSequence(eventId, UUID.randomUUID().toString())).isNull()
    }

    @Test
    fun `승격되기 전에는 Active가 아니다`() {
        // given
        val eventId = System.nanoTime()
        val token = UUID.randomUUID().toString()
        queueRepository.register(eventId, token)

        // when & then
        assertThat(queueRepository.isActive(eventId, token)).isFalse()
    }

    @Test
    fun `승격되면 Active로 표시된다`() {
        // given
        val eventId = System.nanoTime()
        val token = UUID.randomUUID().toString()
        queueRepository.register(eventId, token)
        queueRepository.promote(eventId, 1)

        // when & then
        assertThat(queueRepository.isActive(eventId, token)).isTrue()
    }

    @Test
    fun `아직 아무도 승격되지 않았으면 마지막 승격 순번은 0이다`() {
        // given
        val eventId = System.nanoTime()

        // when & then
        assertThat(queueRepository.lastPromotedSequence(eventId)).isEqualTo(0L)
    }

    @Test
    fun `승격하면 마지막 승격 순번이 갱신된다`() {
        // given
        val eventId = System.nanoTime()
        queueRepository.register(eventId, UUID.randomUUID().toString())
        val seq2 = queueRepository.register(eventId, UUID.randomUUID().toString())

        // when
        queueRepository.promote(eventId, 2)

        // then
        assertThat(queueRepository.lastPromotedSequence(eventId)).isEqualTo(seq2)
    }
}
