package com.ticketrush.payment.application

import com.ticketrush.event.domain.EventRepositoryPort
import com.ticketrush.payment.PaymentPolicyProperties
import com.ticketrush.payment.domain.Payment
import com.ticketrush.payment.domain.PaymentConflictException
import com.ticketrush.payment.domain.PaymentRepositoryPort
import com.ticketrush.payment.domain.PaymentStatus
import com.ticketrush.reservation.domain.Reservation
import com.ticketrush.reservation.domain.ReservationRepositoryPort
import com.ticketrush.reservation.domain.ReservationStatus
import com.ticketrush.support.IntegrationTest
import com.ticketrush.support.공연_하나_저장
import com.ticketrush.support.예약_하나_저장
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime
import kotlin.test.Test

class PaymentClaimServiceTest : IntegrationTest() {
    @Autowired
    lateinit var eventRepository: EventRepositoryPort

    @Autowired
    lateinit var reservationRepository: ReservationRepositoryPort

    @Autowired
    lateinit var paymentRepository: PaymentRepositoryPort

    @Autowired
    lateinit var claimService: PaymentClaimService

    @Autowired
    lateinit var policy: PaymentPolicyProperties

    @Autowired
    lateinit var clock: Clock

    @Test
    @Transactional
    fun `첫 클레임은 PENDING Payment를 새로 만든다`() {
        val event = eventRepository.공연_하나_저장()
        val reservation = reservationRepository.예약_하나_저장(event = event, amount = 100_000)

        val claimed = 클레임(reservation, LocalDateTime.now(clock))

        assertThat(claimed.status).isEqualTo(PaymentStatus.PENDING)
        assertThat(claimed.reservationId).isEqualTo(reservation.id)
        assertThat(claimed.amount).isEqualTo(100_000)
    }

    @Test
    @Transactional
    fun `fresh한 PENDING이 이미 있으면 충돌 예외를 던진다`() {
        val event = eventRepository.공연_하나_저장()
        val reservation = reservationRepository.예약_하나_저장(event = event)
        val now = LocalDateTime.now(clock)
        클레임(reservation, now)

        assertThatThrownBy {
            클레임(reservation, now)
        }.isInstanceOf(PaymentConflictException::class.java)
    }

    @Test
    @Transactional
    fun `stale한 PENDING은 새로 만들지 않고 그대로 이어받는다`() {
        val event = eventRepository.공연_하나_저장()
        val reservation = reservationRepository.예약_하나_저장(event = event)
        val claimedAt = LocalDateTime.now(clock)
        val first = 클레임(reservation, claimedAt)

        val muchLater = claimedAt.plus(policy.staleClaimTimeout).plusSeconds(1)
        val takenOver = 클레임(reservation, muchLater)

        assertThat(takenOver.id).isEqualTo(first.id)
        assertThat(takenOver.status).isEqualTo(PaymentStatus.PENDING)
    }

    @Test
    @Transactional
    fun `이미 SUCCESS 등 최종 상태인 Payment는 그대로 반환한다`() {
        val event = eventRepository.공연_하나_저장()
        val reservation = reservationRepository.예약_하나_저장(event = event)
        val claimed = 클레임(reservation, LocalDateTime.now(clock))
        claimed.markSuccess("PG-TXN-1", LocalDateTime.now(clock))
        paymentRepository.save(claimed)

        val result = 클레임(reservation, LocalDateTime.now(clock))

        assertThat(result.id).isEqualTo(claimed.id)
        assertThat(result.status).isEqualTo(PaymentStatus.SUCCESS)
    }

    @Test
    @Transactional
    fun `이미 SUCCESS인 Payment는 늦게 도착한 시도의 paymentKey와 orderId로 덮어쓰지 않는다`() {
        val event = eventRepository.공연_하나_저장()
        val reservation = reservationRepository.예약_하나_저장(event = event)
        val claimed = 클레임(reservation, LocalDateTime.now(clock))
        claimed.markSuccess("PG-TXN-1", LocalDateTime.now(clock))
        paymentRepository.save(claimed)

        val result = 클레임(reservation, LocalDateTime.now(clock), paymentKey = "late-payment-key", orderId = "late-order-id")

        assertThat(result.tossPaymentKey).isEqualTo("test-payment-key")
        assertThat(result.tossOrderId).isEqualTo(reservation.idempotencyKey.toString())
    }

    @Test
    @Transactional
    fun `이미 FAILED인 Payment도 새 시도의 paymentKey와 orderId로 덮어쓰지 않는다`() {
        val event = eventRepository.공연_하나_저장()
        val reservation = reservationRepository.예약_하나_저장(event = event)
        val claimed = 클레임(reservation, LocalDateTime.now(clock))
        claimed.markFailed()
        paymentRepository.save(claimed)

        val result = 클레임(reservation, LocalDateTime.now(clock), paymentKey = "late-payment-key", orderId = "late-order-id")

        assertThat(result.tossPaymentKey).isEqualTo("test-payment-key")
        assertThat(result.tossOrderId).isEqualTo(reservation.idempotencyKey.toString())
    }

    @Test
    @Transactional
    fun `applySuccess는 PENDING을 SUCCESS로 바꾸고 예약번호를 배정한다`() {
        val event = eventRepository.공연_하나_저장()
        val reservation = reservationRepository.예약_하나_저장(event = event)
        val claimed = 클레임(reservation, LocalDateTime.now(clock))

        val result = claimService.applySuccess(claimed, reservation, "PG-TX-001", LocalDateTime.now(clock))

        assertThat(result.payment.status).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(result.reservationNo).isNotBlank()
        assertThat(reservationRepository.findById(reservation.id)?.status).isEqualTo(ReservationStatus.PAID)
    }

    @Test
    @Transactional
    fun `applyFailure는 PENDING을 FAILED로 바꾸고 홀드를 단축한다`() {
        val event = eventRepository.공연_하나_저장()
        val reservation = reservationRepository.예약_하나_저장(event = event)
        val claimed = 클레임(reservation, LocalDateTime.now(clock))

        claimService.applyFailure(claimed, reservation, LocalDateTime.now(clock), reason = "한도 초과")

        assertThat(paymentRepository.findByReservationId(reservation.id)?.status).isEqualTo(PaymentStatus.FAILED)
    }

    private fun 클레임(
        reservation: Reservation,
        now: LocalDateTime,
        paymentKey: String = "test-payment-key",
        orderId: String = reservation.idempotencyKey.toString(),
    ): Payment = claimService.claimOrTakeOver(reservation.id, reservation.amount, paymentKey, orderId, now)
}
