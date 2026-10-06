package com.ticketrush.payment.application

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.ticketrush.event.domain.EventRepositoryPort
import com.ticketrush.payment.PaymentPolicyProperties
import com.ticketrush.payment.domain.Payment
import com.ticketrush.payment.domain.PaymentConflictException
import com.ticketrush.payment.domain.PaymentGatewayPort
import com.ticketrush.payment.domain.PaymentGatewayResult
import com.ticketrush.payment.domain.PaymentHistoryRepositoryPort
import com.ticketrush.payment.domain.PaymentRepositoryPort
import com.ticketrush.payment.domain.PaymentStatus
import com.ticketrush.payment.domain.RefundIdempotencyKey
import com.ticketrush.reservation.domain.Reservation
import com.ticketrush.reservation.domain.ReservationRepositoryPort
import com.ticketrush.support.IntegrationTest
import com.ticketrush.support.공연_하나_저장
import com.ticketrush.support.예약_하나_저장
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

// 롤백되는 @Transactional 테스트라 백그라운드 스케줄러 틱이 이 행을 보지 못하므로 retry(staleBefore)를 직접 부른다.
@Import(PaymentRefundRetrySchedulerTest.MockConfig::class)
class PaymentRefundRetrySchedulerTest : IntegrationTest() {
    @Autowired
    lateinit var eventRepository: EventRepositoryPort

    @Autowired
    lateinit var reservationRepository: ReservationRepositoryPort

    @Autowired
    lateinit var paymentRepository: PaymentRepositoryPort

    @Autowired
    lateinit var paymentHistoryRepository: PaymentHistoryRepositoryPort

    @Autowired
    lateinit var retryScheduler: PaymentRefundRetryScheduler

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
    private val schedulerLogger = LoggerFactory.getLogger(PaymentRefundRetryScheduler::class.java.name) as Logger
    private val recorderLogger = LoggerFactory.getLogger(PaymentRefundRecorder::class.java.name) as Logger

    @BeforeEach
    fun reset() {
        clearMocks(paymentGateway)
        logs.start()
        schedulerLogger.addAppender(logs)
        recorderLogger.addAppender(logs)
    }

    @AfterEach
    fun releaseLogs() {
        schedulerLogger.detachAppender(logs)
        recorderLogger.detachAppender(logs)
        logs.stop()
    }

    @Test
    @Transactional
    fun `환불에 성공하면 REFUNDED로 바뀌고 이력이 남으며 예약 키에서 유도한 환불 키로 요청한다`() {
        val reservation = 예약()
        취소된_결제(reservation, pgTransactionId = "pk-retry")
        every { paymentGateway.refund(any(), any(), any()) } returns PaymentGatewayResult.Approved(pgTransactionId = "pk-retry")

        retryScheduler.retry(staleBefore())

        val payment = 결제(reservation)
        assertThat(payment.status).isEqualTo(PaymentStatus.REFUNDED)
        val history = paymentHistoryRepository.findAllByPaymentId(payment.id).single()
        assertThat(history.fromStatus).isEqualTo(PaymentStatus.CANCELED)
        assertThat(history.toStatus).isEqualTo(PaymentStatus.REFUNDED)
        assertThat(history.reason).contains("환불 재시도 성공")
        val firstKey = RefundIdempotencyKey.of(reservation.idempotencyKey, 0)
        verify(exactly = 1) { paymentGateway.refund("pk-retry", reservation.amount, firstKey) }
    }

    @Test
    @Transactional
    fun `환불이 거절되면 시도 횟수가 올라 DB에 저장되고 결제는 CANCELED로 남으며 이력에 사유가 남는다`() {
        val reservation = 예약()
        취소된_결제(reservation)
        every { paymentGateway.refund(any(), any(), any()) } returns PaymentGatewayResult.Declined("환불 한도 초과")

        retryScheduler.retry(staleBefore())

        entityManager.flush()
        entityManager.clear()
        val payment = 결제(reservation)
        assertThat(payment.status).isEqualTo(PaymentStatus.CANCELED)
        assertThat(payment.refundAttemptCount).isEqualTo(1)
        val history = paymentHistoryRepository.findAllByPaymentId(payment.id).single()
        assertThat(history.fromStatus).isEqualTo(PaymentStatus.CANCELED)
        assertThat(history.toStatus).isEqualTo(PaymentStatus.CANCELED)
        assertThat(history.reason).contains("1/${policy.refundMaxAttempts}").contains("환불 한도 초과")
        assertThat(logs.list.filter { it.level == Level.ERROR }).isEmpty()
    }

    @Test
    @Transactional
    fun `환불 호출이 예외를 던져도 시도 횟수가 올라 저장되고 결제는 CANCELED로 남는다`() {
        val reservation = 예약()
        취소된_결제(reservation)
        every { paymentGateway.refund(any(), any(), any()) } throws HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE)

        retryScheduler.retry(staleBefore())

        entityManager.flush()
        entityManager.clear()
        val payment = 결제(reservation)
        assertThat(payment.status).isEqualTo(PaymentStatus.CANCELED)
        assertThat(payment.refundAttemptCount).isEqualTo(1)
    }

    @Test
    @Transactional
    fun `환불이 PG에서 아직 처리 중이라는 충돌이면 시도 횟수와 이력을 늘리지 않고 ERROR도 남기지 않는다`() {
        val reservation = 예약()
        취소된_결제(reservation)
        every { paymentGateway.refund(any(), any(), any()) } throws PaymentConflictException()

        retryScheduler.retry(staleBefore())

        entityManager.flush()
        entityManager.clear()
        val payment = 결제(reservation)
        assertThat(payment.status).isEqualTo(PaymentStatus.CANCELED)
        assertThat(payment.refundAttemptCount).isEqualTo(0)
        assertThat(paymentHistoryRepository.findAllByPaymentId(payment.id)).isEmpty()
        assertThat(logs.list.filter { it.level == Level.ERROR }).isEmpty()
    }

    @Test
    @Transactional
    fun `환불이 실패하면 다음 재시도는 시도 횟수를 섞은 새 환불 키로 요청한다`() {
        // Toss는 에러 응답도 같은 키로 재생하므로 같은 키로 다시 보내면 복구되지 않는다
        val reservation = 예약()
        취소된_결제(reservation, pgTransactionId = "pk-key-rotation")
        every { paymentGateway.refund(any(), any(), any()) } returns PaymentGatewayResult.Declined("거절")

        retryScheduler.retry(staleBefore())
        오래된_행으로_만들기(결제(reservation))
        retryScheduler.retry(staleBefore())

        verify(exactly = 1) { paymentGateway.refund("pk-key-rotation", any(), RefundIdempotencyKey.of(reservation.idempotencyKey, 0)) }
        verify(exactly = 1) { paymentGateway.refund("pk-key-rotation", any(), RefundIdempotencyKey.of(reservation.idempotencyKey, 1)) }
    }

    @Test
    @Transactional
    fun `실패한 행은 재시도 간격이 지나기 전에는 환불을 다시 요청하지 않는다`() {
        val reservation = 예약()
        취소된_결제(reservation)
        every { paymentGateway.refund(any(), any(), any()) } returns PaymentGatewayResult.Declined("거절")

        retryScheduler.retry(staleBefore())
        retryScheduler.retry(staleBefore())

        verify(exactly = 1) { paymentGateway.refund(any(), any(), any()) }
    }

    @Test
    @Transactional
    fun `한 행의 환불이 실패해도 다음 행은 계속 처리한다`() {
        val failing = 예약()
        val healthy = 예약()
        취소된_결제(failing, pgTransactionId = "pk-failing")
        취소된_결제(healthy, pgTransactionId = "pk-healthy")
        every { paymentGateway.refund("pk-failing", any(), any()) } throws HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE)
        every { paymentGateway.refund("pk-healthy", any(), any()) } returns PaymentGatewayResult.Approved(pgTransactionId = "pk-healthy")

        retryScheduler.retry(staleBefore())

        assertThat(결제(failing).status).isEqualTo(PaymentStatus.CANCELED)
        assertThat(결제(healthy).status).isEqualTo(PaymentStatus.REFUNDED)
    }

    @Test
    @Transactional
    fun `시도 횟수가 한도에 도달하면 ERROR를 한 번 남기고 이후에는 조회되지 않아 환불을 다시 요청하지 않는다`() {
        val reservation = 예약()
        val payment = 취소된_결제(reservation, refundAttemptCount = policy.refundMaxAttempts - 1)
        every { paymentGateway.refund(any(), any(), any()) } returns PaymentGatewayResult.Declined("거절")

        retryScheduler.retry(staleBefore())

        entityManager.flush()
        entityManager.clear()
        assertThat(결제(reservation).refundAttemptCount).isEqualTo(policy.refundMaxAttempts)
        val alert = logs.list.single { it.level == Level.ERROR }.formattedMessage
        assertThat(alert).contains("수동 처리 필요").contains("paymentId=${payment.id}").contains("reservationId=${reservation.id}")

        오래된_행으로_만들기(결제(reservation))
        retryScheduler.retry(staleBefore())

        verify(exactly = 1) { paymentGateway.refund(any(), any(), any()) }
        assertThat(logs.list.filter { it.level == Level.ERROR }).hasSize(1)
    }

    @Test
    @Transactional
    fun `실패 사유가 이력 컬럼 길이를 넘어도 잘라서 저장한다`() {
        val reservation = 예약()
        취소된_결제(reservation)
        every { paymentGateway.refund(any(), any(), any()) } returns PaymentGatewayResult.Declined("x".repeat(300))

        retryScheduler.retry(staleBefore())

        entityManager.flush()
        entityManager.clear()
        val payment = 결제(reservation)
        assertThat(payment.refundAttemptCount).isEqualTo(1)
        assertThat(paymentHistoryRepository.findAllByPaymentId(payment.id).single().reason).hasSize(200)
    }

    @Test
    @Transactional
    fun `최근에 취소된 결제는 환불을 다시 요청하지 않는다`() {
        val reservation = 예약()
        취소된_결제(reservation, stale = false)

        retryScheduler.retry(staleBefore())

        verify(exactly = 0) { paymentGateway.refund(any(), any(), any()) }
        assertThat(결제(reservation).refundAttemptCount).isEqualTo(0)
    }

    @Test
    @Transactional
    fun `CANCELED가 아닌 결제는 오래돼도 건드리지 않는다`() {
        val refunded = 예약()
        val success = 예약()
        val pending = 예약()
        결제_저장(refunded, PaymentStatus.REFUNDED)
        결제_저장(success, PaymentStatus.SUCCESS)
        결제_저장(pending, PaymentStatus.PENDING)

        retryScheduler.retry(staleBefore())

        verify(exactly = 0) { paymentGateway.refund(any(), any(), any()) }
        assertThat(결제(refunded).status).isEqualTo(PaymentStatus.REFUNDED)
        assertThat(결제(success).status).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(결제(pending).status).isEqualTo(PaymentStatus.PENDING)
    }

    private fun 예약(): Reservation = reservationRepository.예약_하나_저장(event = eventRepository.공연_하나_저장())

    private fun 취소된_결제(
        reservation: Reservation,
        pgTransactionId: String = "pk-${UUID.randomUUID()}",
        refundAttemptCount: Int = 0,
        stale: Boolean = true,
    ): Payment = 결제_저장(reservation, PaymentStatus.CANCELED, pgTransactionId, refundAttemptCount, stale)

    private fun 결제_저장(
        reservation: Reservation,
        status: PaymentStatus,
        pgTransactionId: String = "pk-${UUID.randomUUID()}",
        refundAttemptCount: Int = 0,
        stale: Boolean = true,
    ): Payment {
        val payment =
            paymentRepository.save(
                Payment(
                    reservationId = reservation.id,
                    amount = reservation.amount,
                    status = status,
                    pgTransactionId = pgTransactionId,
                    paidAt = LocalDateTime.now(clock),
                    refundAttemptCount = refundAttemptCount,
                ),
            )
        if (stale) 오래된_행으로_만들기(payment)
        return payment
    }

    private fun 결제(reservation: Reservation): Payment = requireNotNull(paymentRepository.findByReservationId(reservation.id))

    // 감사 필드는 JPA flush 때만 갱신되므로 JDBC로 시간을 되돌린다
    private fun 오래된_행으로_만들기(payment: Payment) {
        entityManager.flush()
        jdbcTemplate.update("UPDATE payment SET updated_at = updated_at - interval '1 hour' WHERE id = ?", payment.id)
        entityManager.clear()
    }

    private fun staleBefore(): LocalDateTime = LocalDateTime.now(clock).minus(policy.refundRetryDelay)
}
