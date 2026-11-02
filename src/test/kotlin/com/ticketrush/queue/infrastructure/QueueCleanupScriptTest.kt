package com.ticketrush.queue.infrastructure

import com.ticketrush.queue.domain.EventSchedule
import com.ticketrush.queue.domain.RegisterResult
import com.ticketrush.support.IntegrationTest
import com.ticketrush.support.testClientIp
import com.ticketrush.support.열린_공연에_등록
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Instant
import java.util.UUID
import kotlin.test.Test

class QueueCleanupScriptTest : IntegrationTest() {
    @Autowired
    lateinit var redisTemplate: StringRedisTemplate

    @Autowired
    lateinit var repository: QueueRedisAdapter

    private val eventId = System.nanoTime()
    private val otherEventId = eventId + 1
    private val farFuture: Instant = Instant.parse("2999-01-01T00:00:00Z")

    private fun 등록(event: Long = eventId): String = UUID.randomUUID().toString().also { repository.열린_공연에_등록(event, it) }

    private fun 남은_키(event: Long): List<String> =
        listOf("waiting", "seq", "lastPromotedSeq").filter {
            redisTemplate.hasKey("queue:$event:$it")
        }

    @Test
    fun `허용 시각이 지났으면 대기 줄과 순번 키를 지우고 활성 공연 집합에서 뺀다`() {
        // given
        등록()
        등록()
        repository.promote(eventId, 1)
        assertThat(남은_키(eventId)).containsExactlyInAnyOrder("waiting", "seq", "lastPromotedSeq")

        // when
        val cleaned = repository.cleanup(eventId, Instant.EPOCH)

        // then
        assertThat(cleaned).isTrue()
        assertThat(남은_키(eventId)).isEmpty()
        assertThat(repository.activeEventIds()).doesNotContain(eventId)
    }

    @Test
    fun `허용 시각 전이면 아무것도 지우지 않는다`() {
        // given
        등록()

        // when
        val cleaned = repository.cleanup(eventId, farFuture)

        // then
        assertThat(cleaned).isFalse()
        assertThat(남은_키(eventId)).containsExactlyInAnyOrder("waiting", "seq")
        assertThat(repository.activeEventIds()).contains(eventId)
        assertThat(redisTemplate.hasKey("queue:$eventId:cleaned")).isFalse()
    }

    @Test
    fun `이미 정리된 공연을 다시 정리해도 안전하다`() {
        // given
        등록()
        repository.cleanup(eventId, Instant.EPOCH)

        // when
        val cleanedAgain = repository.cleanup(eventId, Instant.EPOCH)

        // then
        assertThat(cleanedAgain).isFalse()
        assertThat(남은_키(eventId)).isEmpty()
    }

    @Test
    fun `다른 공연의 키는 건드리지 않는다`() {
        // given
        등록(eventId)
        등록(otherEventId)

        // when
        repository.cleanup(eventId, Instant.EPOCH)

        // then
        assertThat(남은_키(otherEventId)).containsExactlyInAnyOrder("waiting", "seq")
        assertThat(repository.activeEventIds()).contains(otherEventId)
    }

    // token 키는 대기자 수만큼 있어 정리에서 지우지 않는다. 지우지 않은 채 조회가 토큰 TTL을 늘리면 줄어들지 않는 순번을 보게 된다
    @Test
    fun `정리된 공연의 대기자는 token 키가 남아 있어도 조회가 무효로 나온다`() {
        // given
        val token = 등록()
        assertThat(repository.findStatus(eventId, token)).isNotNull()

        // when
        repository.cleanup(eventId, Instant.EPOCH)

        // then
        assertThat(redisTemplate.hasKey("queue:$eventId:token:$token")).isTrue()
        assertThat(repository.findStatus(eventId, token)).isNull()
    }

    // 등록 스크립트는 Redis에 캐시한 시작 시각을 보므로 일정이 늦춰지면 정리된 뒤에도 열려 있는 것으로 판단할 수 있다.
    // 그렇게 등록한 사람의 조회가 정리 표시 때문에 무효가 되므로 정리된 공연은 닫힌 것으로 응답해야 한다
    @Test
    fun `정리된 공연에는 캐시한 시작 시각이 아직 먼 미래여도 등록되지 않는다`() {
        // given
        등록()
        repository.cleanup(eventId, Instant.EPOCH)

        // when
        val result = repository.register(eventId, UUID.randomUUID().toString(), testClientIp, EventSchedule(Instant.EPOCH, farFuture))

        // then
        assertThat(result).isInstanceOf(RegisterResult.Closed::class.java)
        assertThat(남은_키(eventId)).isEmpty()
    }

    @Test
    fun `정리 직전에 승격된 사용자는 활성 키가 살아 있는 동안 조회된다`() {
        // given
        val token = 등록()
        repository.promote(eventId, 1)

        // when
        repository.cleanup(eventId, Instant.EPOCH)

        // then
        assertThat(repository.findStatus(eventId, token)?.active).isTrue()
    }
}
