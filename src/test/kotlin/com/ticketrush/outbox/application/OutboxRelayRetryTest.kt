package com.ticketrush.outbox.application

import com.ticketrush.notification.domain.NotificationPort
import com.ticketrush.outbox.OutboxPolicyProperties
import com.ticketrush.outbox.domain.OutboxEvent
import com.ticketrush.outbox.domain.OutboxRepositoryPort
import com.ticketrush.reservation.domain.Reservation
import com.ticketrush.reservation.domain.ReservationRepositoryPort
import com.ticketrush.reservation.domain.ReservationStatus
import com.ticketrush.shared.PhoneEncryptor
import com.ticketrush.shared.PhoneHash
import com.ticketrush.support.IntegrationTest
import com.ticketrush.support.TestClock
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.Test

// 롤백 테스트는 UPDATE가 DB까지 안 나가 CHECK 제약을 평가하지 못하므로, 커밋하고 DB에서 직접 읽는다.
class OutboxRelayRetryTest : IntegrationTest() {
    @Autowired
    lateinit var outboxRepository: OutboxRepositoryPort

    @Autowired
    lateinit var phoneEncryptor: PhoneEncryptor

    @Autowired
    lateinit var policy: OutboxPolicyProperties

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    private val reservationRepository = mockk<ReservationRepositoryPort>()
    private val notificationPort = mockk<NotificationPort>()

    // 캐시된 다른 컨텍스트의 릴레이(실제 시각 기준)가 이 행을 가로채지 못하게 먼 미래로 고정한다
    private val clock = TestClock(Instant.parse("2100-01-01T00:00:00Z"))
    private val aggregateId = 900_200_001L

    @AfterEach
    fun cleanUp() {
        jdbcTemplate.update("DELETE FROM outbox WHERE aggregate_id = ?", aggregateId)
    }

    @Test
    fun `발송이 계속 실패하면 max-attempts번째 실패에서 FAILED로 끝난다`() {
        // given
        every { reservationRepository.findById(aggregateId) } returns 결제완료_예약()
        every { notificationPort.sendSms(any(), any()) } throws RuntimeException("게이트웨이 오류")
        val relay = OutboxRelayScheduler(outboxRepository, reservationRepository, notificationPort, phoneEncryptor, policy, clock)
        val outboxId = outboxRepository.save(대기중인_이벤트()).id

        // when
        repeat(policy.maxAttempts) {
            relay.relay()
            clock.advanceSeconds(policy.retryDelay.seconds + 1) // 다음 재시도 시각이 지나게 한다
        }

        // then
        val row = jdbcTemplate.queryForMap("SELECT status, attempt_count FROM outbox WHERE id = ?", outboxId)
        assertThat(row["status"]).isEqualTo("FAILED")
        assertThat(row["attempt_count"]).isEqualTo(policy.maxAttempts)
        verify(exactly = policy.maxAttempts) { notificationPort.sendSms(any(), any()) }
    }

    private fun 대기중인_이벤트(): OutboxEvent {
        val now = LocalDateTime.now(clock)
        return OutboxEvent(
            aggregateType = "RESERVATION",
            aggregateId = aggregateId,
            eventType = "RESERVATION_PAID",
            payload = """{"reservationId":$aggregateId}""",
            nextAttemptAt = now,
            createdAt = now,
        )
    }

    // 저장하지 않는 인메모리 예약. dispatch가 읽는 전화번호와 예매번호만 채운다
    private fun 결제완료_예약(): Reservation =
        Reservation(
            eventId = 1L,
            phoneHash = PhoneHash(ByteArray(32) { 1 }),
            encryptedPhone = phoneEncryptor.encrypt("01099999999"),
            quantity = 1,
            amount = 100_000,
            holdToken = UUID.randomUUID(),
            idempotencyKey = UUID.randomUUID(),
            reservationNo = "RES000000001",
            status = ReservationStatus.PAID,
        )
}
