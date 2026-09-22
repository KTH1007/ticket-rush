package com.ticketrush.outbox.application

import com.ticketrush.notification.domain.NotificationPort
import com.ticketrush.outbox.OutboxPolicyProperties
import com.ticketrush.outbox.domain.OutboxEvent
import com.ticketrush.outbox.domain.OutboxRepositoryPort
import com.ticketrush.reservation.domain.ReservationRepositoryPort
import com.ticketrush.shared.PhoneEncryptor
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDateTime

private val logger = KotlinLogging.logger {}

// claim(짧은 트랜잭션)과 실제 발송(트랜잭션 밖, 느릴 수 있는 외부 I/O)을 분리한다.
@Component
class OutboxRelayScheduler(
    private val outboxRepository: OutboxRepositoryPort,
    private val reservationRepository: ReservationRepositoryPort,
    private val notificationPort: NotificationPort,
    private val phoneEncryptor: PhoneEncryptor,
    private val policy: OutboxPolicyProperties,
    private val clock: Clock,
) {
    @Scheduled(fixedRateString = "\${ticket-rush.outbox.poll-interval}")
    fun relay() {
        outboxRepository.claimBatch(policy.chunkSize, LocalDateTime.now(clock)).forEach(::processOne)
    }

    // 어떤 이유로 실패하든(네트워크 오류, 예약 조회 실패 등) 재시도 처리로 넘겨야 한다
    @Suppress("TooGenericExceptionCaught")
    fun processOne(event: OutboxEvent) {
        try {
            dispatch(event)
            outboxRepository.markDone(event.id)
        } catch (e: Exception) {
            logger.warn(e) { "outbox 이벤트 처리 실패: id=${event.id}, eventType=${event.eventType}" }
            outboxRepository.markFailedOrRetry(event.id, LocalDateTime.now(clock), policy.retryDelay, policy.maxAttempts)
        }
    }

    private fun dispatch(event: OutboxEvent) {
        val reservation =
            requireNotNull(reservationRepository.findById(event.aggregateId)) {
                "outbox 이벤트의 예약을 찾을 수 없습니다: eventId=${event.id}, reservationId=${event.aggregateId}"
            }
        val encryptedPhone =
            requireNotNull(reservation.encryptedPhone) {
                "예약에 암호화된 전화번호가 없습니다: reservationId=${reservation.id}"
            }
        val reservationNo =
            requireNotNull(reservation.reservationNo) {
                "예약에 예매번호가 없습니다: reservationId=${reservation.id}"
            }
        val phone = phoneEncryptor.decrypt(encryptedPhone)
        notificationPort.sendSms(phone, messageFor(event.eventType, reservationNo))
    }

    companion object {
        private fun messageFor(
            eventType: String,
            reservationNo: String,
        ): String =
            when (eventType) {
                "RESERVATION_PAID" -> "[티켓러시] 예매가 완료됐습니다. 예매번호: $reservationNo"
                "RESERVATION_CANCELED" -> "[티켓러시] 예매가 취소됐습니다. 예매번호: $reservationNo"
                else -> error("알 수 없는 outbox 이벤트 타입입니다: $eventType")
            }
    }
}
