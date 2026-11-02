package com.ticketrush.queue.infrastructure

import com.ticketrush.queue.QueuePolicyProperties
import com.ticketrush.support.IntegrationTest
import com.ticketrush.support.열린_공연에_등록
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test

// 순번은 seq와 lastPromotedSeq로 계산되므로 줄에 사람이 있는 동안 둘 중 하나라도 만료되면 순번이 틀어진다.
// 대기열이 비었을 때만 만료를 걸어, 활성 집합에서 빠져 정리 스케줄러가 찾지 못하는 공연의 작은 키가 스스로 사라지게 한다
class QueueSequenceKeyTtlTest : IntegrationTest() {
    @Autowired
    lateinit var redisTemplate: StringRedisTemplate

    @Autowired
    lateinit var repository: QueueRedisAdapter

    @Autowired
    lateinit var policy: QueuePolicyProperties

    private val eventId = System.nanoTime()

    private fun 등록(): String = UUID.randomUUID().toString().also { repository.열린_공연에_등록(eventId, it) }

    // -1은 만료 없음, -2는 키 없음
    private fun 남은_초(key: String): Long = redisTemplate.getExpire("queue:$eventId:$key", TimeUnit.SECONDS)

    @Test
    fun `대기열이 비면 순번 키와 마지막 승격 순번 키에 만료가 걸린다`() {
        // given
        등록()
        등록()

        // when
        repository.promote(eventId, 10)

        // then
        val retention = policy.cleanup.retention.toSeconds()
        assertThat(남은_초("seq")).isBetween(1L, retention)
        assertThat(남은_초("lastPromotedSeq")).isBetween(1L, retention)
    }

    @Test
    fun `줄에 사람이 남아 있으면 만료를 걸지 않는다`() {
        // given
        등록()
        등록()
        등록()

        // when
        repository.promote(eventId, 1)

        // then
        assertThat(남은_초("seq")).isEqualTo(-1L)
        assertThat(남은_초("lastPromotedSeq")).isEqualTo(-1L)
    }

    @Test
    fun `비운 뒤 다시 등록하면 만료가 사라지고 순번이 이어진다`() {
        // given
        등록()
        repository.promote(eventId, 10)
        assertThat(남은_초("seq")).isPositive()

        // when
        val token = UUID.randomUUID().toString()
        val sequence = repository.열린_공연에_등록(eventId, token)

        // then
        assertThat(sequence).isEqualTo(2L)
        assertThat(남은_초("seq")).isEqualTo(-1L)
        assertThat(남은_초("lastPromotedSeq")).isEqualTo(-1L)
        val status = repository.findStatus(eventId, token)!!
        assertThat(status.sequence - status.lastPromotedSequence).isEqualTo(1L)
    }
}
