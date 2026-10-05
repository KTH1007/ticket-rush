package com.ticketrush.payment.application

import com.ticketrush.event.domain.EventRepositoryPort
import com.ticketrush.payment.PaymentPolicyProperties
import com.ticketrush.payment.domain.Payment
import com.ticketrush.payment.domain.PaymentConflictException
import com.ticketrush.payment.domain.PaymentHistoryRepositoryPort
import com.ticketrush.payment.domain.PaymentRepositoryPort
import com.ticketrush.payment.domain.PaymentStatus
import com.ticketrush.reservation.domain.GradeRepositoryPort
import com.ticketrush.reservation.domain.Reservation
import com.ticketrush.reservation.domain.ReservationRepositoryPort
import com.ticketrush.reservation.domain.ReservationStatus
import com.ticketrush.reservation.domain.SeatRepositoryPort
import com.ticketrush.reservation.domain.SeatStatus
import com.ticketrush.shared.PhoneHash
import com.ticketrush.support.IntegrationTest
import com.ticketrush.support.공연_하나_저장
import com.ticketrush.support.등급_하나_저장
import com.ticketrush.support.예약_하나_저장
import com.ticketrush.support.홀드된_좌석_하나_저장
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
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
    lateinit var gradeRepository: GradeRepositoryPort

    @Autowired
    lateinit var seatRepository: SeatRepositoryPort

    @Autowired
    lateinit var paymentHistoryRepository: PaymentHistoryRepositoryPort

    @Autowired
    lateinit var claimService: PaymentClaimService

    @Autowired
    lateinit var policy: PaymentPolicyProperties

    @Autowired
    lateinit var clock: Clock

    @PersistenceContext
    lateinit var entityManager: EntityManager

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

    @Test
    @Transactional
    fun `markFailedByReconciliation은 결제만 FAILED로 바꾸고 이력에 사유를 남기며 예약과 좌석의 홀드는 건드리지 않는다`() {
        val reservation = 홀드가_지난_예약과_좌석()
        val claimed = 클레임(reservation, LocalDateTime.now(clock))

        claimService.markFailedByReconciliation(claimed, "Toss 미승인(ABORTED), 예약을 쓸 수 없어 실패 확정")

        assertThat(paymentRepository.findByReservationId(reservation.id)?.status).isEqualTo(PaymentStatus.FAILED)
        val history = paymentHistoryRepository.findAllByPaymentId(claimed.id).single()
        assertThat(history.fromStatus).isEqualTo(PaymentStatus.PENDING)
        assertThat(history.toStatus).isEqualTo(PaymentStatus.FAILED)
        assertThat(history.reason).isEqualTo("Toss 미승인(ABORTED), 예약을 쓸 수 없어 실패 확정")
        assertReservationAndSeatHoldUntouched(reservation)
    }

    private fun 홀드가_지난_예약과_좌석(): Reservation {
        val event = eventRepository.공연_하나_저장()
        val grade = gradeRepository.등급_하나_저장(event)
        val phoneHash = PhoneHash(ByteArray(32) { 1 })
        val reservation = reservationRepository.예약_하나_저장(event, phoneHash = phoneHash, holdExpiresAt = HOLD_PASSED_AT)
        seatRepository.홀드된_좌석_하나_저장(event, grade, reservation.id, phoneHash, slotNo = 1, holdExpiresAt = HOLD_PASSED_AT)
        return reservation
    }

    // 좌석의 홀드 단축은 벌크 UPDATE라 영속성 컨텍스트를 비우고 DB에서 다시 읽어야 보인다
    private fun assertReservationAndSeatHoldUntouched(reservation: Reservation) {
        entityManager.flush()
        entityManager.clear()
        val savedReservation = requireNotNull(reservationRepository.findById(reservation.id))
        assertThat(savedReservation.status).isEqualTo(ReservationStatus.HOLDING)
        assertThat(savedReservation.holdExpiresAt).isEqualTo(HOLD_PASSED_AT)
        val seat = seatRepository.findAllByReservationId(reservation.id).single()
        assertThat(seat.status).isEqualTo(SeatStatus.HELD)
        assertThat(seat.holdExpiresAt).isEqualTo(HOLD_PASSED_AT)
    }

    private fun 클레임(
        reservation: Reservation,
        now: LocalDateTime,
        paymentKey: String = "test-payment-key",
        orderId: String = reservation.idempotencyKey.toString(),
    ): Payment = claimService.claimOrTakeOver(reservation.id, reservation.amount, paymentKey, orderId, now)

    companion object {
        private val HOLD_PASSED_AT: LocalDateTime = LocalDateTime.of(2020, 1, 1, 0, 0)
    }
}
