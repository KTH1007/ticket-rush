package com.ticketrush.outbox.application

import com.ticketrush.outbox.domain.OutboxEvent
import com.ticketrush.outbox.domain.OutboxRepositoryPort
import com.ticketrush.payment.domain.ReservationCanceledEvent
import com.ticketrush.payment.domain.ReservationPaidEvent
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.time.Clock
import java.time.LocalDateTime

// BEFORE_COMMIT을 쓰는 이유: 아직 열려 있는 같은 트랜잭션 안에서 실행돼야 결제확정/취소와
// outbox 기록이 원자적으로 묶인다(AFTER_COMMIT이면 원자성이 깨짐). 이 페이즈는 Spring Modulith의
// 영속 추적(event_publication) 대상에서도 제외된다(그 레지스트리는 AFTER_COMMIT 리스너만 추적함).
@Component
class OutboxEventRecorder(
    private val outboxRepository: OutboxRepositoryPort,
    private val clock: Clock,
) {
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    fun on(event: ReservationPaidEvent) {
        record("RESERVATION_PAID", event.reservationId)
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    fun on(event: ReservationCanceledEvent) {
        record("RESERVATION_CANCELED", event.reservationId)
    }

    private fun record(
        eventType: String,
        reservationId: Long,
    ) {
        val now = LocalDateTime.now(clock)
        outboxRepository.save(
            OutboxEvent(
                aggregateType = "RESERVATION",
                aggregateId = reservationId,
                eventType = eventType,
                payload = """{"reservationId":$reservationId}""",
                nextAttemptAt = now,
                createdAt = now,
            ),
        )
    }
}
