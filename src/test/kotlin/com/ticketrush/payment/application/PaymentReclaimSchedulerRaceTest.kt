package com.ticketrush.payment.application

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.ticketrush.payment.PaymentPolicyProperties
import com.ticketrush.payment.domain.Payment
import com.ticketrush.payment.domain.PaymentConflictException
import com.ticketrush.payment.domain.PaymentGatewayPort
import com.ticketrush.payment.domain.PaymentGatewayResult
import com.ticketrush.payment.domain.PaymentRepositoryPort
import com.ticketrush.payment.domain.PaymentStatus
import com.ticketrush.reservation.domain.Reservation
import com.ticketrush.reservation.domain.ReservationRepositoryPort
import com.ticketrush.reservation.domain.ReservationStatus
import com.ticketrush.shared.PhoneHash
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test

// 목록 조회와 처리 사이에 다른 인스턴스나 사용자 재시도가 같은 결제를 확정하는 경쟁은 reclaim()만으로 만들 수 없어 협력 객체를 목으로 대체한다
class PaymentReclaimSchedulerRaceTest {
    private val paymentRepository = mockk<PaymentRepositoryPort>()
    private val reservationRepository = mockk<ReservationRepositoryPort>()
    private val paymentGateway = mockk<PaymentGatewayPort>()
    private val claimService = mockk<PaymentClaimService>()
    private val clock = Clock.fixed(Instant.parse("2026-10-03T00:00:00Z"), ZoneOffset.UTC)
    private val scheduler =
        PaymentReclaimScheduler(
            paymentRepository,
            reservationRepository,
            paymentGateway,
            claimService,
            PaymentPolicyProperties(staleClaimTimeout = Duration.ofSeconds(10), reclaimInterval = Duration.ofSeconds(5)),
            clock,
        )
    private val logs = ListAppender<ILoggingEvent>()
    private val schedulerLogger = LoggerFactory.getLogger(PaymentReclaimScheduler::class.java.name) as Logger

    @BeforeEach
    fun captureLogs() {
        logs.start()
        schedulerLogger.addAppender(logs)
    }

    @AfterEach
    fun releaseLogs() {
        schedulerLogger.detachAppender(logs)
        logs.stop()
    }

    @Test
    fun `재확인했을 때 결제가 이미 SUCCESS면 대사 알림을 남기지 않는다`() {
        // given: 확정된 결제의 예약은 PAID라 재확인이 없으면 "승인 여부를 모름" ERROR가 잘못 울린다
        stubStaleSnapshot(결제(PaymentStatus.PENDING))
        every { paymentRepository.findByReservationId(RESERVATION_ID) } returns 결제(PaymentStatus.SUCCESS)
        every { reservationRepository.findById(RESERVATION_ID) } returns 예약(ReservationStatus.PAID)

        // when
        scheduler.reclaim(LocalDateTime.now(clock))

        // then
        assertThat(logs.list.filter { it.level == Level.ERROR }).isEmpty()
        verify(exactly = 0) { paymentGateway.charge(any(), any(), any(), any()) }
    }

    @Test
    fun `재확인했을 때 결제가 이미 FAILED면 클레임도 PG 호출도 하지 않는다`() {
        // given: 사용자 재시도가 방금 FAILED로 확정했고 예약은 아직 HOLDING인 상태
        val reservation = 예약(ReservationStatus.HOLDING)
        stubStaleSnapshot(결제(PaymentStatus.PENDING))
        every { paymentRepository.findByReservationId(RESERVATION_ID) } returns 결제(PaymentStatus.FAILED)
        every { reservationRepository.findById(RESERVATION_ID) } returns reservation
        every { claimService.claimOrTakeOver(any(), any(), any(), any(), any()) } returns 결제(PaymentStatus.FAILED)
        every { paymentGateway.charge(any(), any(), any(), any()) } returns PaymentGatewayResult.Approved("pk-late")

        // when
        scheduler.reclaim(LocalDateTime.now(clock))

        // then
        verify(exactly = 0) { claimService.claimOrTakeOver(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { paymentGateway.charge(any(), any(), any(), any()) }
    }

    @Test
    fun `클레임이 이미 확정된 행을 돌려주면 PG를 부르지 않고 결과도 반영하지 않는다`() {
        // given: 재확인 직후 다른 쪽이 확정해서 claimOrTakeOver가 SUCCESS 행을 그대로 돌려주는 경쟁
        val reservation = 예약(ReservationStatus.HOLDING)
        stubStaleSnapshot(결제(PaymentStatus.PENDING))
        every { paymentRepository.findByReservationId(RESERVATION_ID) } returns 결제(PaymentStatus.PENDING)
        every { reservationRepository.findById(RESERVATION_ID) } returns reservation
        every { claimService.claimOrTakeOver(any(), any(), any(), any(), any()) } returns 결제(PaymentStatus.SUCCESS)
        every { paymentGateway.charge(any(), any(), any(), any()) } returns PaymentGatewayResult.Approved("pk-late")
        justRun { claimService.applySuccess(any(), any(), any(), any()) }

        // when
        scheduler.reclaim(LocalDateTime.now(clock))

        // then
        verify(exactly = 0) { paymentGateway.charge(any(), any(), any(), any()) }
        verify(exactly = 0) { claimService.applySuccess(any(), any(), any(), any()) }
        verify(exactly = 0) { claimService.applyFailure(any(), any(), any(), any()) }
    }

    @Test
    fun `재확인한 최신 행의 paymentKey와 orderId로 재시도한다`() {
        // given: 목록 조회 이후 클레임의 키가 바뀐 경우
        val reservation = 예약(ReservationStatus.HOLDING)
        stubStaleSnapshot(결제(PaymentStatus.PENDING, paymentKey = "pk-old", orderId = "order-old"))
        every {
            paymentRepository.findByReservationId(RESERVATION_ID)
        } returns 결제(PaymentStatus.PENDING, paymentKey = "pk-new", orderId = "order-new")
        every { reservationRepository.findById(RESERVATION_ID) } returns reservation
        every { claimService.claimOrTakeOver(any(), any(), any(), any(), any()) } returns 결제(PaymentStatus.PENDING)
        every { paymentGateway.charge(any(), any(), any(), any()) } returns PaymentGatewayResult.Declined("한도 초과")
        justRun { claimService.applyFailure(any(), any(), any(), any()) }

        // when
        scheduler.reclaim(LocalDateTime.now(clock))

        // then
        verify(exactly = 1) { claimService.claimOrTakeOver(RESERVATION_ID, AMOUNT, "pk-new", "order-new", any()) }
        verify(exactly = 1) { paymentGateway.charge("pk-new", "order-new", AMOUNT, reservation.idempotencyKey) }
    }

    @Test
    fun `이전 틱이 아직 돌고 있으면 같은 인스턴스의 다음 틱은 PG를 부르지 않고 끝난다`() {
        // given: PG 호출이 길어지는 사이 다음 틱이 들어온 상황
        stubStaleSnapshot(결제(PaymentStatus.PENDING))
        every { paymentRepository.findByReservationId(RESERVATION_ID) } returns 결제(PaymentStatus.PENDING)
        every { reservationRepository.findById(RESERVATION_ID) } returns 예약(ReservationStatus.HOLDING)
        every { claimService.claimOrTakeOver(any(), any(), any(), any(), any()) } returns 결제(PaymentStatus.PENDING)
        justRun { claimService.applySuccess(any(), any(), any(), any()) }
        every { paymentGateway.charge(any(), any(), any(), any()) } answers {
            scheduler.reclaim(LocalDateTime.now(clock))
            PaymentGatewayResult.Approved("pk-test")
        }

        // when
        scheduler.reclaim(LocalDateTime.now(clock))

        // then
        verify(exactly = 1) { paymentGateway.charge(any(), any(), any(), any()) }
    }

    @Test
    fun `다른 요청과 겹쳐 충돌 예외가 나면 스택트레이스 없이 WARN 한 줄만 남긴다`() {
        stubStaleSnapshot(결제(PaymentStatus.PENDING))
        every { paymentRepository.findByReservationId(RESERVATION_ID) } returns 결제(PaymentStatus.PENDING)
        every { reservationRepository.findById(RESERVATION_ID) } returns 예약(ReservationStatus.HOLDING)
        every { claimService.claimOrTakeOver(any(), any(), any(), any(), any()) } throws PaymentConflictException()

        scheduler.reclaim(LocalDateTime.now(clock))

        assertThat(logs.list.single { it.level == Level.WARN }.throwableProxy).isNull()
    }

    private fun stubStaleSnapshot(snapshot: Payment) {
        every { paymentRepository.findStalePending(any()) } returns listOf(snapshot)
    }

    private fun 결제(
        status: PaymentStatus,
        paymentKey: String = "pk-test",
        orderId: String = "order-test",
    ): Payment =
        Payment(
            id = PAYMENT_ID,
            reservationId = RESERVATION_ID,
            amount = AMOUNT,
            status = status,
            tossPaymentKey = paymentKey,
            tossOrderId = orderId,
        )

    private fun 예약(status: ReservationStatus): Reservation =
        Reservation(
            id = RESERVATION_ID,
            eventId = 1L,
            phoneHash = PhoneHash(ByteArray(32) { 1 }),
            quantity = 1,
            amount = AMOUNT,
            holdToken = UUID.randomUUID(),
            idempotencyKey = UUID.randomUUID(),
            status = status,
            holdExpiresAt = LocalDateTime.of(2030, 1, 1, 0, 0),
        )

    companion object {
        private const val PAYMENT_ID = 1L
        private const val RESERVATION_ID = 10L
        private const val AMOUNT = 100_000
    }
}
