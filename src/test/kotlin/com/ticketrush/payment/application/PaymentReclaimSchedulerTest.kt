package com.ticketrush.payment.application

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.ticketrush.event.domain.Event
import com.ticketrush.event.domain.EventRepositoryPort
import com.ticketrush.payment.PaymentPolicyProperties
import com.ticketrush.payment.domain.ChargeIdempotencyKey
import com.ticketrush.payment.domain.Payment
import com.ticketrush.payment.domain.PaymentGatewayPort
import com.ticketrush.payment.domain.PaymentGatewayResult
import com.ticketrush.payment.domain.PaymentHistoryRepositoryPort
import com.ticketrush.payment.domain.PaymentInquiryResult
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
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.client.HttpServerErrorException
import java.time.Clock
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.Test

// 롤백되는 @Transactional 테스트라 백그라운드 스케줄러 틱이 이 행을 보지 못하므로 reclaim(staleBefore)를 직접 부른다.
@Import(PaymentReclaimSchedulerTest.MockConfig::class)
class PaymentReclaimSchedulerTest : IntegrationTest() {
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
    lateinit var reclaimScheduler: PaymentReclaimScheduler

    @Autowired
    lateinit var paymentGateway: PaymentGatewayPort

    @Autowired
    lateinit var policy: PaymentPolicyProperties

    @Autowired
    lateinit var clock: Clock

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @PersistenceContext
    lateinit var entityManager: EntityManager

    @TestConfiguration
    class MockConfig {
        @Bean
        @Primary
        fun paymentGateway(): PaymentGatewayPort = mockk()
    }

    private val logs = ListAppender<ILoggingEvent>()
    private val schedulerLogger = LoggerFactory.getLogger(PaymentReclaimScheduler::class.java.name) as Logger

    @BeforeEach
    fun reset() {
        clearMocks(paymentGateway)
        logs.start()
        schedulerLogger.addAppender(logs)
    }

    @AfterEach
    fun releaseLogs() {
        schedulerLogger.detachAppender(logs)
        logs.stop()
    }

    @Test
    @Transactional
    fun `stale한 PENDING을 같은 정보로 재시도해서 SUCCESS로 반영한다`() {
        val reservation = reservationRepository.예약_하나_저장(event = 공연())
        클레임(reservation, paymentKey = "pk-stale")
        every {
            paymentGateway.charge(any(), any(), any(), reservation.idempotencyKey)
        } returns PaymentGatewayResult.Approved(pgTransactionId = "pk-stale")

        reclaimScheduler.reclaim(staleBefore = staleBefore())

        val payment = requireNotNull(paymentRepository.findByReservationId(reservation.id))
        assertThat(payment.status).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(payment.pgTransactionId).isEqualTo("pk-stale")
        assertThat(payment.paidAt).isNotNull
        verify(exactly = 1) {
            paymentGateway.charge("pk-stale", reservation.idempotencyKey.toString(), reservation.amount, reservation.idempotencyKey)
        }
    }

    // 새 PG 호출이 홀드 만료와 겹치면 승인된 뒤 반영이 막힌다. 곧 만료될 홀드는 호출하지 않고 만료된 뒤 조회로 정리한다
    @Test
    @Transactional
    fun `홀드가 곧 만료되는 PENDING은 PG를 부르지 않고 이번 틱은 건너뛴다`() {
        val reservation = reservationRepository.예약_하나_저장(event = 공연(), holdExpiresAt = LocalDateTime.now(clock).plusSeconds(5))
        클레임(reservation, paymentKey = "pk-near-expiry")

        reclaimScheduler.reclaim(staleBefore = staleBefore())

        verify(exactly = 0) { paymentGateway.charge(any(), any(), any(), any()) }
        verify(exactly = 0) { paymentGateway.inquire(any()) }
        assertThat(paymentRepository.findByReservationId(reservation.id)?.status).isEqualTo(PaymentStatus.PENDING)
    }

    @Test
    @Transactional
    fun `이전 거절로 승인 거절 횟수가 오른 행은 그 횟수를 섞은 멱등키로 재시도한다`() {
        val reservation = reservationRepository.예약_하나_저장(event = 공연())
        클레임(reservation, paymentKey = "pk-after-decline")
        jdbcTemplate.update("UPDATE payment SET charge_attempt_count = 1 WHERE reservation_id = ?", reservation.id)
        entityManager.clear()
        every { paymentGateway.charge(any(), any(), any(), any()) } returns
            PaymentGatewayResult.Approved(pgTransactionId = "pk-after-decline")

        reclaimScheduler.reclaim(staleBefore = staleBefore())

        val expectedKey = ChargeIdempotencyKey.of(reservation.idempotencyKey, 1)
        val orderId = reservation.idempotencyKey.toString()
        verify(exactly = 1) { paymentGateway.charge("pk-after-decline", orderId, reservation.amount, expectedKey) }
    }

    @Test
    @Transactional
    fun `게이트웨이가 승인 시각을 주면 paidAt은 서버 시각이 아니라 그 승인 시각이다`() {
        val reservation = reservationRepository.예약_하나_저장(event = 공연())
        클레임(reservation)
        val approvedAt = LocalDateTime.of(2020, 1, 1, 10, 15, 30)
        every {
            paymentGateway.charge(any(), any(), any(), reservation.idempotencyKey)
        } returns PaymentGatewayResult.Approved(pgTransactionId = "pk-approved", approvedAt = approvedAt)

        reclaimScheduler.reclaim(staleBefore = staleBefore())

        assertThat(paymentRepository.findByReservationId(reservation.id)?.paidAt).isEqualTo(approvedAt)
    }

    @Test
    @Transactional
    fun `게이트웨이가 거절하면 FAILED로 확정하고 홀드를 단축한다`() {
        val reservation = reservationRepository.예약_하나_저장(event = 공연())
        val originalHoldExpiresAt = requireNotNull(reservation.holdExpiresAt)
        클레임(reservation)
        every { paymentGateway.charge(any(), any(), any(), reservation.idempotencyKey) } returns PaymentGatewayResult.Declined("한도 초과")

        reclaimScheduler.reclaim(staleBefore = staleBefore())

        assertThat(paymentRepository.findByReservationId(reservation.id)?.status).isEqualTo(PaymentStatus.FAILED)
        val updated = requireNotNull(reservationRepository.findById(reservation.id))
        assertThat(updated.holdExpiresAt).isBefore(originalHoldExpiresAt)
    }

    @Test
    @Transactional
    fun `예약이 HOLDING이 아니면 Toss가 승인(DONE)했어도 charge를 부르지 않고 Payment는 PENDING으로 남긴다`() {
        val reservation = 예약_저장(status = ReservationStatus.EXPIRED)
        클레임(reservation, paymentKey = "pk-expired")
        every { paymentGateway.inquire("pk-expired") } returns PaymentInquiryResult.Done("pk-expired")

        reclaimScheduler.reclaim(staleBefore = staleBefore())

        verify(exactly = 0) { paymentGateway.charge(any(), any(), any(), reservation.idempotencyKey) }
        assertThat(paymentRepository.findByReservationId(reservation.id)?.status).isEqualTo(PaymentStatus.PENDING)
    }

    @Test
    @Transactional
    fun `홀드 시각이 지난 예약이면 Toss가 승인(DONE)했어도 charge를 부르지 않고 Payment는 PENDING으로 남긴다`() {
        val reservation = 예약_저장(holdExpiresAt = LocalDateTime.of(2020, 1, 1, 0, 0))
        클레임(reservation, paymentKey = "pk-hold-passed")
        every { paymentGateway.inquire("pk-hold-passed") } returns PaymentInquiryResult.Done("pk-hold-passed")

        reclaimScheduler.reclaim(staleBefore = staleBefore())

        verify(exactly = 0) { paymentGateway.charge(any(), any(), any(), reservation.idempotencyKey) }
        assertThat(paymentRepository.findByReservationId(reservation.id)?.status).isEqualTo(PaymentStatus.PENDING)
    }

    @Test
    @Transactional
    fun `toss_payment_key가 없는 stale PENDING 행은 조회되지 않고 게이트웨이도 부르지 않는다`() {
        val reservation = reservationRepository.예약_하나_저장(event = 공연())
        val payment = paymentRepository.save(Payment(reservationId = reservation.id, amount = reservation.amount))
        오래된_행으로_만들기(payment)

        reclaimScheduler.reclaim(staleBefore = staleBefore())

        assertThat(paymentRepository.findStalePending(staleBefore())).extracting("id").doesNotContain(payment.id)
        verify(exactly = 0) { paymentGateway.charge(any(), any(), any(), reservation.idempotencyKey) }
        assertThat(paymentRepository.findByReservationId(reservation.id)?.status).isEqualTo(PaymentStatus.PENDING)
    }

    @Test
    @Transactional
    fun `게이트웨이가 예외를 던져도 그 행은 PENDING으로 남고 다음 행은 계속 처리한다`() {
        val event = 공연()
        val failing = reservationRepository.예약_하나_저장(event = event)
        val healthy = reservationRepository.예약_하나_저장(event = event)
        클레임(failing, paymentKey = "pk-failing")
        클레임(healthy, paymentKey = "pk-healthy")
        every {
            paymentGateway.charge(any(), any(), any(), failing.idempotencyKey)
        } throws HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE)
        every {
            paymentGateway.charge(any(), any(), any(), healthy.idempotencyKey)
        } returns PaymentGatewayResult.Approved(pgTransactionId = "pk-healthy")

        reclaimScheduler.reclaim(staleBefore = staleBefore())

        assertThat(paymentRepository.findByReservationId(failing.id)?.status).isEqualTo(PaymentStatus.PENDING)
        assertThat(paymentRepository.findByReservationId(healthy.id)?.status).isEqualTo(PaymentStatus.SUCCESS)
    }

    @Test
    @Transactional
    fun `예약을 쓸 수 없는데 Toss가 승인(DONE)했으면 결제와 예약을 그대로 두고 환불 필요 알림을 남긴다`() {
        val reservation = 만료된_예약과_좌석()
        val approvedAt = LocalDateTime.of(2026, 10, 1, 10, 15, 30)
        클레임(reservation, paymentKey = "pk-done")
        every { paymentGateway.inquire("pk-done") } returns PaymentInquiryResult.Done("pk-done", approvedAt)

        reclaimScheduler.reclaim(staleBefore = staleBefore())

        val payment = requireNotNull(paymentRepository.findByReservationId(reservation.id))
        assertThat(payment.status).isEqualTo(PaymentStatus.PENDING)
        // 알림은 로그뿐 아니라 DB에도 남겨서 재시작해도 사라지지 않고 같은 건을 다시 조회하지 않는다
        assertThat(payment.refundRequiredAt).isNotNull()
        assertThat(paymentHistoryRepository.findAllByPaymentId(payment.id).single().reason).contains("환불 필요", "approvedAt=$approvedAt")
        verify(exactly = 0) { paymentGateway.charge(any(), any(), any(), any()) }
        assertReservationAndSeatUntouched(reservation)
        val refundAlert = logs.list.single { it.level == Level.ERROR }.formattedMessage
        assertThat(refundAlert)
            .contains("환불 필요", "reservationId=${reservation.id}", "paymentId=${payment.id}", "approvedAt=$approvedAt")
    }

    @Test
    @Transactional
    fun `승인(DONE)을 확인해 알린 결제는 다음 틱에서 Toss를 다시 조회하지 않고 알림도 반복하지 않는다`() {
        val reservation = 만료된_예약과_좌석()
        클레임(reservation, paymentKey = "pk-done-twice")
        every { paymentGateway.inquire("pk-done-twice") } returns PaymentInquiryResult.Done("pk-done-twice")

        reclaimScheduler.reclaim(staleBefore = staleBefore())
        reclaimScheduler.reclaim(staleBefore = staleBefore())

        verify(exactly = 1) { paymentGateway.inquire("pk-done-twice") }
        assertThat(logs.list.filter { it.level == Level.ERROR }).hasSize(1)
        assertThat(paymentRepository.findByReservationId(reservation.id)?.status).isEqualTo(PaymentStatus.PENDING)
    }

    @Test
    @Transactional
    fun `예약을 쓸 수 없는데 Toss가 승인하지 않았으면 결제만 FAILED로 확정하고 사유를 남기며 예약과 좌석은 그대로 둔다`() {
        val reservation = 만료된_예약과_좌석()
        클레임(reservation, paymentKey = "pk-aborted")
        every { paymentGateway.inquire("pk-aborted") } returns PaymentInquiryResult.NotApproved("ABORTED")

        reclaimScheduler.reclaim(staleBefore = staleBefore())

        val payment = requireNotNull(paymentRepository.findByReservationId(reservation.id))
        assertThat(payment.status).isEqualTo(PaymentStatus.FAILED)
        val history = paymentHistoryRepository.findAllByPaymentId(payment.id).single()
        assertThat(history.fromStatus).isEqualTo(PaymentStatus.PENDING)
        assertThat(history.toStatus).isEqualTo(PaymentStatus.FAILED)
        assertThat(history.reason).contains("ABORTED")
        verify(exactly = 0) { paymentGateway.charge(any(), any(), any(), any()) }
        assertReservationAndSeatUntouched(reservation)
    }

    @Test
    @Transactional
    fun `예약을 쓸 수 없는데 Toss에 결제가 없으면 결제만 FAILED로 확정하고 예약과 좌석은 그대로 둔다`() {
        val reservation = 만료된_예약과_좌석()
        클레임(reservation, paymentKey = "pk-missing")
        every { paymentGateway.inquire("pk-missing") } returns PaymentInquiryResult.NotFound

        reclaimScheduler.reclaim(staleBefore = staleBefore())

        val payment = requireNotNull(paymentRepository.findByReservationId(reservation.id))
        assertThat(payment.status).isEqualTo(PaymentStatus.FAILED)
        val history = paymentHistoryRepository.findAllByPaymentId(payment.id).single()
        assertThat(history.fromStatus).isEqualTo(PaymentStatus.PENDING)
        assertThat(history.toStatus).isEqualTo(PaymentStatus.FAILED)
        assertThat(history.reason).isNotBlank()
        verify(exactly = 0) { paymentGateway.charge(any(), any(), any(), any()) }
        assertReservationAndSeatUntouched(reservation)
    }

    // HoldExpiryScheduler가 만료시킨 EXPIRED 예약이 실제 운영에서 가드에 걸리는 주된 경우다. applyFailure는 HOLDING만 받아 여기서 던진다
    @Test
    @Transactional
    fun `예약이 이미 EXPIRED인데 Toss가 승인하지 않았으면 결제만 FAILED로 확정하고 예약은 그대로 둔다`() {
        val reservation = 예약_저장(status = ReservationStatus.EXPIRED, holdExpiresAt = HOLD_PASSED_AT)
        클레임(reservation, paymentKey = "pk-expired-aborted")
        every { paymentGateway.inquire("pk-expired-aborted") } returns PaymentInquiryResult.NotApproved("ABORTED")

        reclaimScheduler.reclaim(staleBefore = staleBefore())

        assertFailedByReconciliation(reservation)
    }

    @Test
    @Transactional
    fun `예약이 이미 EXPIRED인데 Toss에 결제가 없으면 결제만 FAILED로 확정하고 예약은 그대로 둔다`() {
        val reservation = 예약_저장(status = ReservationStatus.EXPIRED, holdExpiresAt = HOLD_PASSED_AT)
        클레임(reservation, paymentKey = "pk-expired-missing")
        every { paymentGateway.inquire("pk-expired-missing") } returns PaymentInquiryResult.NotFound

        reclaimScheduler.reclaim(staleBefore = staleBefore())

        assertFailedByReconciliation(reservation)
    }

    @Test
    @Transactional
    fun `조회가 예외를 던지면 그 행은 PENDING으로 남고 다음 행은 계속 처리한다`() {
        val failing = 만료된_예약과_좌석()
        val healthy = reservationRepository.예약_하나_저장(event = 공연())
        클레임(failing, paymentKey = "pk-inquiry-fails")
        클레임(healthy, paymentKey = "pk-healthy")
        every { paymentGateway.inquire("pk-inquiry-fails") } throws HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE)
        every {
            paymentGateway.charge(any(), any(), any(), healthy.idempotencyKey)
        } returns PaymentGatewayResult.Approved(pgTransactionId = "pk-healthy")

        reclaimScheduler.reclaim(staleBefore = staleBefore())

        assertThat(paymentRepository.findByReservationId(failing.id)?.status).isEqualTo(PaymentStatus.PENDING)
        assertThat(paymentRepository.findByReservationId(healthy.id)?.status).isEqualTo(PaymentStatus.SUCCESS)
        verify(exactly = 1) { paymentGateway.inquire("pk-inquiry-fails") }
        verify(exactly = 0) { paymentGateway.charge(any(), any(), any(), failing.idempotencyKey) }
    }

    @Test
    @Transactional
    fun `예약이 HOLDING이고 유효하면 조회 없이 charge로 재시도한다`() {
        val reservation = reservationRepository.예약_하나_저장(event = 공연())
        클레임(reservation, paymentKey = "pk-valid")
        every {
            paymentGateway.charge(any(), any(), any(), reservation.idempotencyKey)
        } returns PaymentGatewayResult.Approved(pgTransactionId = "pk-valid")

        reclaimScheduler.reclaim(staleBefore = staleBefore())

        verify(exactly = 0) { paymentGateway.inquire(any()) }
        assertThat(paymentRepository.findByReservationId(reservation.id)?.status).isEqualTo(PaymentStatus.SUCCESS)
    }

    private fun 공연(): Event = eventRepository.공연_하나_저장()

    // 홀드가 이미 지난 HOLDING 예약과 그 좌석. 회수 스케줄러의 예약 상태 가드에 걸리는 행이다
    private fun 만료된_예약과_좌석(): Reservation {
        val event = 공연()
        val grade = gradeRepository.등급_하나_저장(event)
        val phoneHash = PhoneHash(ByteArray(32) { 1 })
        val reservation = reservationRepository.예약_하나_저장(event, phoneHash = phoneHash, holdExpiresAt = HOLD_PASSED_AT)
        seatRepository.홀드된_좌석_하나_저장(event, grade, reservation.id, phoneHash, slotNo = 1, holdExpiresAt = HOLD_PASSED_AT)
        return reservation
    }

    // 좌석의 홀드 단축은 벌크 UPDATE라 영속성 컨텍스트를 비우고 DB에서 다시 읽어야 보인다
    private fun assertReservationAndSeatUntouched(reservation: Reservation) {
        entityManager.flush()
        entityManager.clear()
        val savedReservation = requireNotNull(reservationRepository.findById(reservation.id))
        assertThat(savedReservation.status).isEqualTo(ReservationStatus.HOLDING)
        assertThat(savedReservation.holdExpiresAt).isEqualTo(HOLD_PASSED_AT)
        val seat = seatRepository.findAllByReservationId(reservation.id).single()
        assertThat(seat.status).isEqualTo(SeatStatus.HELD)
        assertThat(seat.holdExpiresAt).isEqualTo(HOLD_PASSED_AT)
    }

    private fun assertFailedByReconciliation(reservation: Reservation) {
        val payment = requireNotNull(paymentRepository.findByReservationId(reservation.id))
        assertThat(payment.status).isEqualTo(PaymentStatus.FAILED)
        val history = paymentHistoryRepository.findAllByPaymentId(payment.id).single()
        assertThat(history.fromStatus).isEqualTo(PaymentStatus.PENDING)
        assertThat(history.toStatus).isEqualTo(PaymentStatus.FAILED)
        assertThat(history.reason).isNotBlank()
        verify(exactly = 0) { paymentGateway.charge(any(), any(), any(), any()) }
        entityManager.flush()
        entityManager.clear()
        val savedReservation = requireNotNull(reservationRepository.findById(reservation.id))
        assertThat(savedReservation.status).isEqualTo(ReservationStatus.EXPIRED)
        assertThat(savedReservation.holdExpiresAt).isEqualTo(HOLD_PASSED_AT)
    }

    private fun 예약_저장(
        status: ReservationStatus = ReservationStatus.HOLDING,
        holdExpiresAt: LocalDateTime = LocalDateTime.of(2030, 1, 1, 0, 0),
    ): Reservation =
        reservationRepository.save(
            Reservation(
                eventId = 공연().id,
                phoneHash = PhoneHash(ByteArray(32) { 1 }),
                quantity = 1,
                amount = 100_000,
                holdToken = UUID.randomUUID(),
                idempotencyKey = UUID.randomUUID(),
                holdExpiresAt = holdExpiresAt,
                status = status,
            ),
        )

    private fun 클레임(
        reservation: Reservation,
        paymentKey: String = "pk-test",
    ) {
        val payment =
            claimService.claimOrTakeOver(
                reservation.id,
                reservation.amount,
                paymentKey,
                reservation.idempotencyKey.toString(),
                LocalDateTime.now(clock),
            )
        오래된_행으로_만들기(payment)
    }

    // 이어받기는 updatedAt이 staleClaimTimeout보다 오래돼야 하고, 감사 필드는 JPA flush 때만 갱신되므로 JDBC로 시간을 되돌린다
    private fun 오래된_행으로_만들기(payment: Payment) {
        entityManager.flush()
        jdbcTemplate.update("UPDATE payment SET updated_at = updated_at - interval '1 hour' WHERE id = ?", payment.id)
        entityManager.clear()
    }

    private fun staleBefore(): LocalDateTime = LocalDateTime.now(clock).minus(policy.staleClaimTimeout)

    companion object {
        private val HOLD_PASSED_AT: LocalDateTime = LocalDateTime.of(2020, 1, 1, 0, 0)
    }
}
