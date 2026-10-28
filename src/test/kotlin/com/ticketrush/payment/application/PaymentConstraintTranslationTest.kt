package com.ticketrush.payment.application

import com.ticketrush.event.domain.EventRepositoryPort
import com.ticketrush.payment.PaymentPolicyProperties
import com.ticketrush.payment.domain.Payment
import com.ticketrush.payment.domain.PaymentConflictException
import com.ticketrush.payment.domain.PaymentOrderMismatchException
import com.ticketrush.payment.domain.PaymentRepositoryPort
import com.ticketrush.payment.domain.PaymentStatus
import com.ticketrush.reservation.SeatPolicyProperties
import com.ticketrush.reservation.domain.Reservation
import com.ticketrush.reservation.domain.ReservationRepositoryPort
import com.ticketrush.reservation.domain.SeatMarkSyncPort
import com.ticketrush.support.IntegrationTest
import com.ticketrush.support.공연_하나_저장
import com.ticketrush.support.예약_하나_저장
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.Test

// 앱 검증을 일부러 비켜 가게 만들어, 번역이 DB 위반에서 나온 것임을 보장한다
class PaymentConstraintTranslationTest : IntegrationTest() {
    @Autowired
    lateinit var eventRepository: EventRepositoryPort

    @Autowired
    lateinit var reservationRepository: ReservationRepositoryPort

    @Autowired
    lateinit var paymentRepository: PaymentRepositoryPort

    @Autowired
    lateinit var paymentClaimService: PaymentClaimService

    @Autowired
    lateinit var paymentCommandService: PaymentCommandService

    @Autowired
    lateinit var seatUpdater: PaymentSeatUpdater

    @Autowired
    lateinit var reservationNoGenerator: ReservationNoGenerator

    @Autowired
    lateinit var seatPolicy: SeatPolicyProperties

    @Autowired
    lateinit var historyRecorder: PaymentHistoryRecorder

    @Autowired
    lateinit var eventPublisher: ApplicationEventPublisher

    @Autowired
    lateinit var policy: PaymentPolicyProperties

    @Autowired
    lateinit var transactionTemplate: TransactionTemplate

    @Autowired
    lateinit var seatMarkSync: SeatMarkSyncPort

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    private val now = LocalDateTime.of(2026, 1, 1, 0, 0)

    @Test
    fun `이미 결제가 있는 예약에 새 결제를 만들다 uk_payment_reservation에 걸리면 충돌로 번역한다`() {
        // given
        val reservation = 예약_저장()
        결제_저장(reservation, tossPaymentKey = 새_결제키())
        val service = 결제_조회를_비워서_돌려주는_서비스()

        // when & then
        assertThatThrownBy {
            service.claimOrTakeOver(reservation.id, reservation.amount, 새_결제키(), reservation.idempotencyKey.toString(), now)
        }.isInstanceOf(PaymentConflictException::class.java)
    }

    @Test
    fun `uk_payment_reservation이 아닌 제약 위반은 번역하지 않고 그대로 던진다`() {
        // given
        val reservation = 예약_저장()

        // when & then
        assertThatThrownBy {
            paymentClaimService.claimOrTakeOver(reservation.id, -1, 새_결제키(), reservation.idempotencyKey.toString(), now)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
            .hasMessageContaining("ck_payment_amount")
    }

    @Test
    fun `다른 예약이 쓰는 결제 키로 요청하면 uk_payment_toss_payment_key 위반을 주문 불일치로 번역한다`() {
        // given
        val usedKey = 새_결제키()
        결제_저장(예약_저장(), tossPaymentKey = usedKey)
        val reservation = 예약_저장()

        // when & then
        assertThatThrownBy {
            paymentCommandService.confirmPayment(
                reservationId = reservation.id,
                holdToken = reservation.holdToken,
                paymentKey = usedKey,
                orderId = reservation.idempotencyKey.toString(),
                amount = reservation.amount,
            )
        }.isInstanceOf(PaymentOrderMismatchException::class.java)
    }

    @Test
    fun `번역에 쓰는 제약 이름이 DB에 실제로 있다`() {
        listOf(PaymentConstraints.RESERVATION, PaymentConstraints.TOSS_PAYMENT_KEY).forEach { name ->
            val count = jdbcTemplate.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname = ?", Long::class.java, name)

            assertThat(count).describedAs(name).isEqualTo(1L)
        }
    }

    // 다른 요청이 먼저 결제를 만든 직후처럼, 조회는 비어 있는데 INSERT 시점에는 이미 있는 상황을 만든다
    private fun 결제_조회를_비워서_돌려주는_서비스(): PaymentClaimService {
        val delegate = paymentRepository
        val blind =
            object : PaymentRepositoryPort by delegate {
                override fun findByReservationId(reservationId: Long): Payment? = null
            }
        return PaymentClaimService(
            blind,
            reservationRepository,
            seatUpdater,
            reservationNoGenerator,
            seatPolicy,
            historyRecorder,
            eventPublisher,
            policy,
            transactionTemplate,
            seatMarkSync,
        )
    }

    private fun 예약_저장(): Reservation = reservationRepository.예약_하나_저장(event = eventRepository.공연_하나_저장())

    // 최종 상태로 저장해 회수 스케줄러가 건드리지 않게 한다
    private fun 결제_저장(
        reservation: Reservation,
        tossPaymentKey: String,
    ): Payment =
        paymentRepository.save(
            Payment(
                reservationId = reservation.id,
                amount = reservation.amount,
                pgTransactionId = "pg-${UUID.randomUUID()}",
                status = PaymentStatus.SUCCESS,
                paidAt = now,
                tossPaymentKey = tossPaymentKey,
            ),
        )

    // 컨테이너를 재사용하면 이전 실행이 남긴 결제 키와 겹칠 수 있어 매번 새로 만든다
    private fun 새_결제키(): String = "key-${UUID.randomUUID()}"
}
