package com.ticketrush.outbox.application

import com.ticketrush.notification.domain.NotificationPort
import com.ticketrush.outbox.OutboxPolicyProperties
import com.ticketrush.outbox.domain.OutboxEvent
import com.ticketrush.outbox.domain.OutboxRepositoryPort
import com.ticketrush.outbox.domain.OutboxStatus
import com.ticketrush.reservation.domain.Reservation
import com.ticketrush.shared.EncryptedPhone
import com.ticketrush.shared.PhoneEncryptor
import com.ticketrush.support.IntegrationTest
import com.ticketrush.support.공연_하나_저장
import com.ticketrush.support.예약_하나_저장
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import kotlin.test.Test

@Import(OutboxRelaySchedulerTest.MockConfig::class)
class OutboxRelaySchedulerTest : IntegrationTest() {
    @Autowired
    lateinit var eventRepository: com.ticketrush.event.domain.EventRepositoryPort

    @Autowired
    lateinit var reservationRepository: com.ticketrush.reservation.domain.ReservationRepositoryPort

    @Autowired
    lateinit var outboxRepository: OutboxRepositoryPort

    @Autowired
    lateinit var relayScheduler: OutboxRelayScheduler

    @Autowired
    lateinit var notificationPort: NotificationPort

    @Autowired
    lateinit var phoneEncryptor: PhoneEncryptor

    @Autowired
    lateinit var policy: OutboxPolicyProperties

    @TestConfiguration
    class MockConfig {
        @Bean
        @Primary
        fun notificationPort(): NotificationPort = mockk(relaxed = true)
    }

    // notificationPort는 Spring 테스트 컨텍스트가 캐싱돼 테스트 클래스 안에서 같은 mock을
    // 공유한다. 한 테스트가 건 스텁이 다음 테스트에 남지 않게 매번 초기화한다.
    @BeforeEach
    fun resetNotificationMock() {
        clearMocks(notificationPort)
    }

    @Test
    @Transactional
    fun `PENDING 이벤트를 처리하면 알림을 보내고 DONE으로 바뀐다`() {
        // given
        val encryptedPhone = phoneEncryptor.encrypt("01099999999")
        val reservation = 결제완료_예약_준비(encryptedPhone)
        val outboxEvent = 대기중인_outbox_이벤트_저장(reservation.id)

        // when
        val claimed = requireNotNull(outboxRepository.claimBatch(limit = 10, now = LocalDateTime.now()).firstOrNull { it.id == outboxEvent.id })
        relayScheduler.processOne(claimed)

        // then
        verify { notificationPort.sendSms("01099999999", "[티켓러시] 예매가 완료됐습니다. 예매번호: ${reservation.reservationNo}") }
        assertThat(outboxRepository.findById(outboxEvent.id)?.status).isEqualTo(OutboxStatus.DONE)
    }

    @Test
    @Transactional
    fun `발송이 실패하면 attempt_count가 늘고 PENDING으로 재시도 대기한다`() {
        // given
        every { notificationPort.sendSms(any(), any()) } throws RuntimeException("게이트웨이 오류")
        val encryptedPhone = phoneEncryptor.encrypt("01099999999")
        val reservation = 결제완료_예약_준비(encryptedPhone)
        val outboxEvent = 대기중인_outbox_이벤트_저장(reservation.id)

        // when
        val claimed = requireNotNull(outboxRepository.claimBatch(limit = 10, now = LocalDateTime.now()).firstOrNull { it.id == outboxEvent.id })
        relayScheduler.processOne(claimed)

        // then
        val updated = outboxRepository.findById(outboxEvent.id)
        assertThat(updated?.attemptCount).isEqualTo(1)
        assertThat(updated?.status).isEqualTo(OutboxStatus.PENDING)
    }

    @Test
    @Transactional
    fun `markFailedOrRetry를 max-attempts만큼 반복하면 FAILED가 된다`() {
        // given
        val now = LocalDateTime.now()
        val event = outboxRepository.save(
            OutboxEvent(
                aggregateType = "RESERVATION",
                aggregateId = 999L,
                eventType = "RESERVATION_PAID",
                payload = "{}",
                nextAttemptAt = now,
                createdAt = now,
            ),
        )
        outboxRepository.claimBatch(limit = 10, now = now) // PROCESSING으로 만듦

        // when — recordFailure는 PROCESSING 상태에서만 되므로 매번 되돌린 뒤 기록
        repeat(policy.maxAttempts) {
            val current = requireNotNull(outboxRepository.findById(event.id))
            current.markProcessingForTest()
            outboxRepository.save(current)
            outboxRepository.markFailedOrRetry(event.id, now, policy.retryDelay, policy.maxAttempts)
        }

        // then
        assertThat(outboxRepository.findById(event.id)?.status).isEqualTo(OutboxStatus.FAILED)
        assertThat(outboxRepository.findById(event.id)?.attemptCount).isEqualTo(policy.maxAttempts)
    }

    private fun 대기중인_outbox_이벤트_저장(reservationId: Long): OutboxEvent =
        outboxRepository.save(
            OutboxEvent(
                aggregateType = "RESERVATION",
                aggregateId = reservationId,
                eventType = "RESERVATION_PAID",
                payload = """{"reservationId":$reservationId}""",
                nextAttemptAt = LocalDateTime.now(),
                createdAt = LocalDateTime.now(),
            ),
        )

    private fun 결제완료_예약_준비(encryptedPhone: EncryptedPhone): Reservation {
        val event = eventRepository.공연_하나_저장()
        val reservation = reservationRepository.예약_하나_저장(event = event, encryptedPhone = encryptedPhone)
        reservation.confirmPayment()
        reservation.assignReservationNo("RES${reservation.id}".padEnd(12, '0').take(12))
        return reservationRepository.save(reservation)
    }
}
