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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime
import java.util.UUID
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

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

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

    // 같은 paymentKey와 orderId로 이어받으면 바뀌는 값이 없어 UPDATE가 안 나가고, 행이 계속 stale로 보여 다른 요청이 또 이어받는다
    @Test
    @Transactional
    fun `같은 키로 stale한 PENDING을 이어받아도 DB에 반영되어 바로 다른 요청이 이어받으면 충돌한다`() {
        val event = eventRepository.공연_하나_저장()
        val reservation = reservationRepository.예약_하나_저장(event = event)
        val first = 클레임(reservation, LocalDateTime.now(clock))
        entityManager.flush()
        jdbcTemplate.update("UPDATE payment SET updated_at = updated_at - interval '1 hour' WHERE id = ?", first.id)
        entityManager.clear()

        클레임(reservation, LocalDateTime.now(clock))
        entityManager.flush()

        // 기준 시각을 과거로 둬서 저장된 updatedAt이 새로 써졌을 때만 충돌한다. 안 써졌다면 1시간 전 값이라 이어받아져 실패한다
        assertThatThrownBy { 클레임(reservation, LocalDateTime.now(clock).minusMinutes(1)) }
            .isInstanceOf(PaymentConflictException::class.java)
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

    // FAILED 상태로 두면 재시도 중 응답이 타임아웃나 Toss가 승인해도 회수 스케줄러(PENDING만 조회)가 대사하지 못한다
    @Test
    @Transactional
    fun `FAILED인 Payment는 새 시도에서 PENDING으로 다시 열고 새 paymentKey와 orderId를 기록한다`() {
        val event = eventRepository.공연_하나_저장()
        val reservation = reservationRepository.예약_하나_저장(event = event)
        val claimed = 클레임(reservation, LocalDateTime.now(clock))
        claimed.markFailed()
        paymentRepository.save(claimed)

        val result = 클레임(reservation, LocalDateTime.now(clock), paymentKey = "retry-payment-key", orderId = "retry-order-id")

        assertThat(result.id).isEqualTo(claimed.id)
        assertThat(result.status).isEqualTo(PaymentStatus.PENDING)
        assertThat(result.tossPaymentKey).isEqualTo("retry-payment-key")
        assertThat(result.tossOrderId).isEqualTo("retry-order-id")
        val reopened = paymentHistoryRepository.findAllByPaymentId(claimed.id).single { it.toStatus == PaymentStatus.PENDING }
        assertThat(reopened.fromStatus).isEqualTo(PaymentStatus.FAILED)
    }

    @Test
    @Transactional
    fun `다시 연 PENDING은 신선해서 바로 다른 요청이 이어받으려 하면 충돌 예외를 던진다`() {
        val event = eventRepository.공연_하나_저장()
        val reservation = reservationRepository.예약_하나_저장(event = event)
        val claimed = 클레임(reservation, LocalDateTime.now(clock))
        claimed.markFailed()
        paymentRepository.save(claimed)
        클레임(reservation, LocalDateTime.now(clock), paymentKey = "retry-payment-key")

        assertThatThrownBy { 클레임(reservation, LocalDateTime.now(clock), paymentKey = "concurrent-payment-key") }
            .isInstanceOf(PaymentConflictException::class.java)
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

        entityManager.flush()
        entityManager.clear()
        val failed = requireNotNull(paymentRepository.findByReservationId(reservation.id))
        assertThat(failed.status).isEqualTo(PaymentStatus.FAILED)
        // 확정 거절이면 횟수를 올려야 다음 시도가 새 멱등키를 쓴다(Toss가 같은 키의 에러를 재생하므로)
        assertThat(failed.chargeAttemptCount).isEqualTo(1)
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

    // HoldExpiryScheduler가 만료시킨 EXPIRED 예약. applyFailure는 HOLDING만 받으므로 결제 상태만 닫는 이 경로가 따로 필요하다
    @Test
    @Transactional
    fun `markFailedByReconciliation은 이미 EXPIRED인 예약에서도 결제만 FAILED로 바꾸고 예약은 그대로 둔다`() {
        val reservation = 이미_만료된_예약()
        val claimed = 클레임(reservation, LocalDateTime.now(clock))

        claimService.markFailedByReconciliation(claimed, "Toss에 결제 내역 없음, 예약을 쓸 수 없어 실패 확정")

        assertThat(paymentRepository.findByReservationId(reservation.id)?.status).isEqualTo(PaymentStatus.FAILED)
        // 예약을 더 쓸 수 없어 재시도가 없으므로 키를 바꿀 필요가 없다
        assertThat(paymentRepository.findByReservationId(reservation.id)?.chargeAttemptCount).isEqualTo(0)
        val history = paymentHistoryRepository.findAllByPaymentId(claimed.id).single()
        assertThat(history.fromStatus).isEqualTo(PaymentStatus.PENDING)
        assertThat(history.toStatus).isEqualTo(PaymentStatus.FAILED)
        entityManager.flush()
        entityManager.clear()
        val savedReservation = requireNotNull(reservationRepository.findById(reservation.id))
        assertThat(savedReservation.status).isEqualTo(ReservationStatus.EXPIRED)
        assertThat(savedReservation.holdExpiresAt).isEqualTo(HOLD_PASSED_AT)
    }

    // 돈이 움직이는 판단이라 자동 환불하지 않고 PENDING 그대로 두되, 사람이 처리할 건을 DB에 남겨 재시작해도 사라지지 않게 한다
    @Test
    @Transactional
    fun `markRefundRequired는 환불 필요 시각과 이력을 DB에 남기고 결제 상태와 예약은 건드리지 않는다`() {
        val reservation = 이미_만료된_예약()
        val claimed = 클레임(reservation, LocalDateTime.now(clock))

        claimService.markRefundRequired(claimed, LocalDateTime.now(clock), "환불 필요: Toss 승인(DONE), 예약 만료")

        entityManager.flush()
        entityManager.clear()
        val payment = requireNotNull(paymentRepository.findByReservationId(reservation.id))
        assertThat(payment.status).isEqualTo(PaymentStatus.PENDING)
        assertThat(payment.refundRequiredAt).isNotNull()
        val history = paymentHistoryRepository.findAllByPaymentId(payment.id).single { it.reason?.contains("환불 필요") == true }
        assertThat(history.fromStatus).isEqualTo(PaymentStatus.PENDING)
        assertThat(history.toStatus).isEqualTo(PaymentStatus.PENDING)
        assertThat(requireNotNull(reservationRepository.findById(reservation.id)).status).isEqualTo(ReservationStatus.EXPIRED)
    }

    private fun 이미_만료된_예약(): Reservation =
        reservationRepository.save(
            Reservation(
                eventId = eventRepository.공연_하나_저장().id,
                phoneHash = PhoneHash(ByteArray(32) { 1 }),
                quantity = 1,
                amount = 100_000,
                holdToken = UUID.randomUUID(),
                idempotencyKey = UUID.randomUUID(),
                holdExpiresAt = HOLD_PASSED_AT,
                status = ReservationStatus.EXPIRED,
            ),
        )

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
