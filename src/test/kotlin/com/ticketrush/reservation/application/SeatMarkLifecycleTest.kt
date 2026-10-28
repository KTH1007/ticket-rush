package com.ticketrush.reservation.application

import com.ticketrush.event.domain.EventRepositoryPort
import com.ticketrush.payment.PaymentPolicyProperties
import com.ticketrush.payment.application.PaymentClaimService
import com.ticketrush.payment.application.PaymentHistoryRecorder
import com.ticketrush.payment.application.PaymentSeatUpdater
import com.ticketrush.payment.application.ReservationNoGenerator
import com.ticketrush.payment.domain.PaymentRepositoryPort
import com.ticketrush.reservation.SeatPolicyProperties
import com.ticketrush.reservation.domain.GradeRepositoryPort
import com.ticketrush.reservation.domain.Reservation
import com.ticketrush.reservation.domain.ReservationRepositoryPort
import com.ticketrush.reservation.domain.SeatHoldFilterPort
import com.ticketrush.reservation.domain.SeatHoldShortenedEvent
import com.ticketrush.reservation.domain.SeatMarkSyncPort
import com.ticketrush.reservation.domain.SeatRepositoryPort
import com.ticketrush.reservation.domain.SeatStatus
import com.ticketrush.reservation.domain.SeatsReturnedEvent
import com.ticketrush.reservation.domain.SeatsSoldEvent
import com.ticketrush.shared.PhoneHash
import com.ticketrush.support.IntegrationTest
import com.ticketrush.support.공연_하나_저장
import com.ticketrush.support.등급_하나_저장
import com.ticketrush.support.예약_하나_저장
import com.ticketrush.support.홀드된_좌석_하나_저장
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test

// 커밋된 뒤의 Redis 상태를 봐야 해서 테스트를 트랜잭션으로 감싸지 않는다
class SeatMarkLifecycleTest : IntegrationTest() {
    @Autowired
    lateinit var eventRepository: EventRepositoryPort

    @Autowired
    lateinit var gradeRepository: GradeRepositoryPort

    @Autowired
    lateinit var seatRepository: SeatRepositoryPort

    @Autowired
    lateinit var reservationRepository: ReservationRepositoryPort

    @Autowired
    lateinit var claimService: PaymentClaimService

    @Autowired
    lateinit var seatHoldFilter: SeatHoldFilterPort

    @Autowired
    lateinit var redisTemplate: StringRedisTemplate

    @Autowired
    lateinit var seatPolicy: SeatPolicyProperties

    @Autowired
    lateinit var clock: Clock

    @Autowired
    lateinit var paymentRepository: PaymentRepositoryPort

    @Autowired
    lateinit var seatUpdater: PaymentSeatUpdater

    @Autowired
    lateinit var reservationNoGenerator: ReservationNoGenerator

    @Autowired
    lateinit var historyRecorder: PaymentHistoryRecorder

    @Autowired
    lateinit var eventPublisher: ApplicationEventPublisher

    @Autowired
    lateinit var paymentPolicy: PaymentPolicyProperties

    @Autowired
    lateinit var transactionTemplate: TransactionTemplate

    @Test
    fun `결제를 확정하면 내 마크의 TTL이 판매 마크 시간으로 늘어난다`() {
        // given
        val held = 마크가_있는_홀드()
        val payment = 클레임(held.reservation)

        // when
        claimService.applySuccess(payment, held.reservation, "PG-TX-MARK", LocalDateTime.now(clock))

        // then
        assertThat(남은_시간(held))
            .isGreaterThan(seatPolicy.holdTtl.toMillis())
            .isLessThanOrEqualTo(seatPolicy.soldMarkTtl.toMillis())
    }

    @Test
    fun `결제가 실패하면 내 마크의 TTL이 결제 실패 홀드 시간 이하로 줄어든다`() {
        // given
        val held = 마크가_있는_홀드()
        val payment = 클레임(held.reservation)

        // when
        claimService.applyFailure(payment, held.reservation, LocalDateTime.now(clock), reason = "한도 초과")

        // then
        assertThat(남은_시간(held)).isBetween(1L, seatPolicy.paymentFailedHoldTtl.toMillis())
    }

    @Test
    fun `트랜잭션이 롤백되면 마크가 그대로다`() {
        // given: 이미 PENDING이 아닌 결제로 확정을 시도하면 좌석을 SOLD로 바꾼 뒤 예외로 롤백된다
        val held = 마크가_있는_홀드()
        val payment = 클레임(held.reservation).also { it.markFailed() }

        // when & then
        assertThatThrownBy { claimService.applySuccess(payment, held.reservation, "PG-TX-ROLLBACK", LocalDateTime.now(clock)) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(남은_시간(held)).isLessThanOrEqualTo(seatPolicy.holdTtl.toMillis())
        assertThat(seatRepository.findAllByReservationId(held.reservation.id).single().status).isEqualTo(SeatStatus.HELD)
    }

    @Test
    fun `결제 확정과 실패의 마크 동기화는 트랜잭션이 끝난 뒤에 호출된다`() {
        // given
        val sync = RecordingSeatMarkSync()
        val service = 동기화를_기록하는_서비스(sync)
        val confirmed = 마크가_있는_홀드()
        val failed = 마크가_있는_홀드()

        // when
        service.applySuccess(클레임(confirmed.reservation), confirmed.reservation, "PG-TX-AFTER", LocalDateTime.now(clock))
        service.applyFailure(클레임(failed.reservation), failed.reservation, LocalDateTime.now(clock), reason = "한도 초과")

        // then
        assertThat(sync.transactionActiveAtCall).containsExactly(false, false)
    }

    @Test
    fun `트랜잭션이 롤백되면 마크 동기화를 호출하지 않는다`() {
        // given
        val sync = RecordingSeatMarkSync()
        val service = 동기화를_기록하는_서비스(sync)
        val held = 마크가_있는_홀드()
        val payment = 클레임(held.reservation).also { it.markFailed() }

        // when
        assertThatThrownBy { service.applySuccess(payment, held.reservation, "PG-TX-NO-SYNC", LocalDateTime.now(clock)) }
            .isInstanceOf(IllegalStateException::class.java)

        // then
        assertThat(sync.transactionActiveAtCall).isEmpty()
    }

    private fun 동기화를_기록하는_서비스(sync: SeatMarkSyncPort) =
        PaymentClaimService(
            paymentRepository,
            reservationRepository,
            seatUpdater,
            reservationNoGenerator,
            seatPolicy,
            historyRecorder,
            eventPublisher,
            paymentPolicy,
            transactionTemplate,
            sync,
        )

    private fun 마크가_있는_홀드(): HeldSeat {
        val event = eventRepository.공연_하나_저장()
        val grade = gradeRepository.등급_하나_저장(event)
        val phoneHash = PhoneHash(ByteArray(32) { 1 })
        val reservation = reservationRepository.예약_하나_저장(event, phoneHash = phoneHash)
        val seat = seatRepository.홀드된_좌석_하나_저장(event, grade, reservation.id, phoneHash, slotNo = 1)
        assertThat(seatHoldFilter.tryClaim(event.id, listOf(seat.id), reservation.holdToken.toString())).isTrue()
        return HeldSeat(reservation, seat.id)
    }

    private fun 클레임(reservation: Reservation) =
        claimService.claimOrTakeOver(
            reservation.id,
            reservation.amount,
            "payment-key-${UUID.randomUUID()}",
            UUID.randomUUID().toString(),
            LocalDateTime.now(clock),
        )

    private fun 남은_시간(held: HeldSeat): Long =
        redisTemplate.getExpire("seat:hold:${held.reservation.eventId}:${held.seatId}", TimeUnit.MILLISECONDS)

    private class HeldSeat(
        val reservation: Reservation,
        val seatId: Long,
    )

    // 호출된 시점에 트랜잭션이 열려 있었는지만 기록한다
    private class RecordingSeatMarkSync : SeatMarkSyncPort {
        val transactionActiveAtCall = mutableListOf<Boolean>()

        override fun onSold(event: SeatsSoldEvent) {
            transactionActiveAtCall += TransactionSynchronizationManager.isActualTransactionActive()
        }

        override fun onHoldShortened(event: SeatHoldShortenedEvent) {
            transactionActiveAtCall += TransactionSynchronizationManager.isActualTransactionActive()
        }

        override fun onReturned(event: SeatsReturnedEvent) {
            transactionActiveAtCall += TransactionSynchronizationManager.isActualTransactionActive()
        }
    }
}
