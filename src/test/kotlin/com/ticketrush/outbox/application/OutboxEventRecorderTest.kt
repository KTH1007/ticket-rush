package com.ticketrush.outbox.application

import com.ticketrush.event.domain.EventRepositoryPort
import com.ticketrush.outbox.domain.OutboxRepositoryPort
import com.ticketrush.payment.domain.ReservationCanceledEvent
import com.ticketrush.payment.domain.ReservationPaidEvent
import com.ticketrush.reservation.domain.ReservationRepositoryPort
import com.ticketrush.support.IntegrationTest
import com.ticketrush.support.공연_하나_저장
import com.ticketrush.support.예약_하나_저장
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime
import kotlin.test.Test

class OutboxEventRecorderTest : IntegrationTest() {
    @Autowired
    lateinit var eventRepository: EventRepositoryPort

    @Autowired
    lateinit var reservationRepository: ReservationRepositoryPort

    @Autowired
    lateinit var eventPublisher: ApplicationEventPublisher

    @Autowired
    lateinit var outboxRepository: OutboxRepositoryPort

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `ReservationPaidEvent를 발행한 트랜잭션이 커밋되면 outbox에 PENDING 상태로 기록된다`() {
        // given & when
        실행_후_커밋 { eventPublisher.publishEvent(ReservationPaidEvent(reservationId = 123L)) }

        // then
        val recorded = outboxRepository.claimBatch(limit = 100, now = LocalDateTime.now()).singleOrNull { it.aggregateId == 123L }
        assertThat(recorded).isNotNull
        assertThat(recorded!!.aggregateType).isEqualTo("RESERVATION")
        assertThat(recorded.eventType).isEqualTo("RESERVATION_PAID")
        // jsonb 컬럼은 저장한 텍스트를 그대로 보존하지 않고 정규화해서 돌려준다(콜론 뒤 공백 추가 등)
        assertThat(recorded.payload).isEqualTo("""{"reservationId": 123}""")
    }

    @Test
    fun `ReservationCanceledEvent를 발행한 트랜잭션이 커밋되면 RESERVATION_CANCELED로 기록된다`() {
        // given & when
        실행_후_커밋 { eventPublisher.publishEvent(ReservationCanceledEvent(reservationId = 456L)) }

        // then
        val recorded = outboxRepository.claimBatch(limit = 100, now = LocalDateTime.now()).single { it.aggregateId == 456L }
        assertThat(recorded.eventType).isEqualTo("RESERVATION_CANCELED")
    }

    @Test
    fun `같은 트랜잭션이 롤백되면 outbox 기록도 남지 않는다`() {
        // given
        val event = eventRepository.공연_하나_저장()
        var reservationId = 0L

        // when
        val caught =
            runCatching {
                실행_후_커밋 {
                    val reservation = reservationRepository.예약_하나_저장(event)
                    reservationId = reservation.id
                    eventPublisher.publishEvent(ReservationPaidEvent(reservationId = reservation.id))
                    error("의도적 롤백")
                }
            }

        // then
        assertThat(caught.isFailure).isTrue()
        assertThat(reservationRepository.findById(reservationId)).isNull()
        val events = outboxRepository.claimBatch(limit = 100, now = LocalDateTime.now())
        assertThat(events).noneMatch { it.aggregateId == reservationId }
    }

    private fun 실행_후_커밋(block: () -> Unit) {
        TransactionTemplate(transactionManager).executeWithoutResult { block() }
    }
}
