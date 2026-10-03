package com.ticketrush.payment.application

import com.ticketrush.event.domain.Event
import com.ticketrush.event.domain.EventRepositoryPort
import com.ticketrush.payment.PaymentPolicyProperties
import com.ticketrush.payment.domain.Payment
import com.ticketrush.payment.domain.PaymentGatewayPort
import com.ticketrush.payment.domain.PaymentGatewayResult
import com.ticketrush.payment.domain.PaymentRepositoryPort
import com.ticketrush.payment.domain.PaymentStatus
import com.ticketrush.reservation.domain.Reservation
import com.ticketrush.reservation.domain.ReservationRepositoryPort
import com.ticketrush.reservation.domain.ReservationStatus
import com.ticketrush.shared.PhoneHash
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
import org.junit.jupiter.api.BeforeEach
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

    @BeforeEach
    fun reset() {
        clearMocks(paymentGateway)
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
    fun `예약이 HOLDING이 아니면 게이트웨이를 부르지 않고 Payment는 PENDING으로 남긴다`() {
        val reservation = 예약_저장(status = ReservationStatus.EXPIRED)
        클레임(reservation)

        reclaimScheduler.reclaim(staleBefore = staleBefore())

        verify(exactly = 0) { paymentGateway.charge(any(), any(), any(), reservation.idempotencyKey) }
        assertThat(paymentRepository.findByReservationId(reservation.id)?.status).isEqualTo(PaymentStatus.PENDING)
    }

    @Test
    @Transactional
    fun `홀드 시각이 지난 예약이면 게이트웨이를 부르지 않고 Payment는 PENDING으로 남긴다`() {
        val reservation = 예약_저장(holdExpiresAt = LocalDateTime.of(2020, 1, 1, 0, 0))
        클레임(reservation)

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

    private fun 공연(): Event = eventRepository.공연_하나_저장()

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
}
