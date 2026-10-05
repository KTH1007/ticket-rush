package com.ticketrush.outbox.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.time.Duration
import java.time.LocalDateTime
import kotlin.test.Test

class OutboxEventTest {
    private val now = LocalDateTime.of(2026, 1, 1, 0, 0)

    @Test
    fun `PENDING 상태로 생성된다`() {
        // when
        val event = 새_이벤트()

        // then
        assertThat(event.status).isEqualTo(OutboxStatus.PENDING)
        assertThat(event.attemptCount).isEqualTo(0)
        assertThat(event.nextAttemptAt).isEqualTo(now)
    }

    @Test
    fun `PROCESSING 상태에서 완료 처리하면 DONE이 된다`() {
        // given
        val event = 프로세싱_이벤트()

        // when
        event.markDone()

        // then
        assertThat(event.status).isEqualTo(OutboxStatus.DONE)
    }

    @Test
    fun `PENDING 상태에서 완료 처리하려 하면 예외가 발생한다`() {
        // given
        val event = 새_이벤트()

        // when & then
        assertThatThrownBy { event.markDone() }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `실패 기록 시 시도 횟수가 늘고 임계치 미만이면 PENDING으로 재시도 대기한다`() {
        // given
        val event = 프로세싱_이벤트()

        // when
        event.recordFailure(now, Duration.ofSeconds(30), maxAttempts = 5)

        // then
        assertThat(event.attemptCount).isEqualTo(1)
        assertThat(event.status).isEqualTo(OutboxStatus.PENDING)
        assertThat(event.nextAttemptAt).isEqualTo(now.plusSeconds(30))
    }

    @Test
    fun `실패 기록이 임계치에 도달하면 FAILED가 된다`() {
        // given
        val event = 프로세싱_이벤트()
        repeat(4) {
            event.recordFailure(now, Duration.ofSeconds(30), maxAttempts = 5)
            event.markProcessingForTest()
        }

        // when
        event.recordFailure(now, Duration.ofSeconds(30), maxAttempts = 5)

        // then
        assertThat(event.attemptCount).isEqualTo(5)
        assertThat(event.status).isEqualTo(OutboxStatus.FAILED)
    }

    @Test
    fun `실패 기록으로 PENDING에 돌아가면 claimedAt을 비운다`() {
        // given
        val event = claim된_이벤트()

        // when
        event.recordFailure(now, Duration.ofSeconds(30), maxAttempts = 5)

        // then
        assertThat(event.status).isEqualTo(OutboxStatus.PENDING)
        assertThat(event.claimedAt).isNull()
    }

    private fun 새_이벤트(): OutboxEvent =
        OutboxEvent(
            aggregateType = "RESERVATION",
            aggregateId = 1L,
            eventType = "RESERVATION_PAID",
            payload = """{"reservationId":1}""",
            nextAttemptAt = now,
            createdAt = now,
        )

    private fun 프로세싱_이벤트(): OutboxEvent = 새_이벤트().also { it.markProcessingForTest() }

    // 프로덕션 claim과 같이 PROCESSING이면서 claimedAt이 채워진 상태
    private fun claim된_이벤트(): OutboxEvent =
        OutboxEvent(
            aggregateType = "RESERVATION",
            aggregateId = 1L,
            eventType = "RESERVATION_PAID",
            payload = """{"reservationId":1}""",
            status = OutboxStatus.PROCESSING,
            nextAttemptAt = now,
            claimedAt = now,
            createdAt = now,
        )
}
