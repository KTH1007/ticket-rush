package com.ticketrush.payment.application

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.ticketrush.event.domain.EventRepositoryPort
import com.ticketrush.payment.domain.Payment
import com.ticketrush.payment.domain.PaymentConflictException
import com.ticketrush.payment.domain.PaymentGatewayPort
import com.ticketrush.payment.domain.PaymentGatewayResult
import com.ticketrush.payment.domain.PaymentHistoryRepositoryPort
import com.ticketrush.payment.domain.PaymentRepositoryPort
import com.ticketrush.payment.domain.PaymentStatus
import com.ticketrush.payment.domain.RefundIdempotencyKey
import com.ticketrush.reservation.domain.GradeRepositoryPort
import com.ticketrush.reservation.domain.Reservation
import com.ticketrush.reservation.domain.ReservationAlreadyCanceledException
import com.ticketrush.reservation.domain.ReservationLookupFailedException
import com.ticketrush.reservation.domain.ReservationRepositoryPort
import com.ticketrush.reservation.domain.ReservationStatus
import com.ticketrush.reservation.domain.Seat
import com.ticketrush.reservation.domain.SeatRepositoryPort
import com.ticketrush.reservation.domain.SeatStatus
import com.ticketrush.shared.PhoneHash
import com.ticketrush.shared.PhoneHasher
import com.ticketrush.support.IntegrationTest
import com.ticketrush.support.공연_하나_저장
import com.ticketrush.support.등급_하나_저장
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import java.sql.Connection
import java.time.LocalDateTime
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test

@Import(PaymentCancelCommandServiceTest.MockConfig::class)
class PaymentCancelCommandServiceTest : IntegrationTest() {
    @Autowired
    lateinit var eventRepository: EventRepositoryPort

    @Autowired
    lateinit var gradeRepository: GradeRepositoryPort

    @Autowired
    lateinit var seatRepository: SeatRepositoryPort

    @Autowired
    lateinit var reservationRepository: ReservationRepositoryPort

    @Autowired
    lateinit var paymentRepository: PaymentRepositoryPort

    @Autowired
    lateinit var paymentHistoryRepository: PaymentHistoryRepositoryPort

    @Autowired
    lateinit var paymentGateway: PaymentGatewayPort

    @Autowired
    lateinit var phoneHasher: PhoneHasher

    @Autowired
    lateinit var cancelService: PaymentCancelCommandService

    @Autowired
    lateinit var dataSource: DataSource

    @TestConfiguration
    class MockConfig {
        @Bean
        @Primary
        fun paymentGateway(): PaymentGatewayPort = mockk()
    }

    @Autowired
    lateinit var outboxRepository: com.ticketrush.outbox.domain.OutboxRepositoryPort

    private val logs = ListAppender<ILoggingEvent>()
    private val serviceLogger = LoggerFactory.getLogger(PaymentCancelCommandService::class.java.name) as Logger

    @BeforeEach
    fun captureLogs() {
        logs.start()
        serviceLogger.addAppender(logs)
    }

    @AfterEach
    fun releaseLogs() {
        serviceLogger.detachAppender(logs)
        logs.stop()
    }

    @Test
    fun `취소하면 outbox에 RESERVATION_CANCELED 이벤트가 같이 커밋된다`() {
        // given
        val reservation = 결제완료_예약_준비()
        every { paymentGateway.refund(any(), any(), any()) } returns PaymentGatewayResult.Approved("FAKE-REFUND-6")

        // when
        cancelService.cancel(reservation.reservationNo!!, PHONE)

        // then
        val recorded = outboxRepository.findByAggregateId(reservation.id).singleOrNull()
        assertThat(recorded).isNotNull
        assertThat(recorded!!.eventType).isEqualTo("RESERVATION_CANCELED")
    }

    // 환불 호출이 취소 확정과 같은 트랜잭션이면, 환불 성공 뒤 커밋이 실패했을 때 돈은 돌아갔는데 예약이 PAID로 롤백된다
    @Test
    fun `환불을 호출하는 시점에는 취소 확정이 이미 커밋돼 있다`() {
        // given
        val reservation = 결제완료_예약_준비()
        var statusesAtRefund: Pair<String?, String?>? = null
        every { paymentGateway.refund(any(), any(), any()) } answers {
            val reservationStatus = committedStatus("SELECT status FROM reservation WHERE id = ?", reservation.id)
            val paymentStatus = committedStatus("SELECT status FROM payment WHERE reservation_id = ?", reservation.id)
            statusesAtRefund = reservationStatus to paymentStatus
            PaymentGatewayResult.Approved("FAKE-REFUND-COMMIT-ORDER")
        }

        // when
        cancelService.cancel(reservation.reservationNo!!, PHONE)

        // then
        assertThat(statusesAtRefund).isEqualTo("CANCELED" to "CANCELED")
    }

    @Test
    fun `PAID 상태의 예약을 취소하면 CANCELED로 바뀌고 좌석이 AVAILABLE로 돌아간다`() {
        // given
        val reservation = 결제완료_예약_준비()
        every { paymentGateway.refund(any(), any(), any()) } returns PaymentGatewayResult.Approved("FAKE-REFUND-1")

        // when
        cancelService.cancel(reservation.reservationNo!!, PHONE)

        // then
        val updated = requireNotNull(reservationRepository.findById(reservation.id))
        assertThat(updated.status).isEqualTo(ReservationStatus.CANCELED)
        val available = seatRepository.findAllByEventId(reservation.eventId).single { it.status == SeatStatus.AVAILABLE }
        assertThat(available.reservationId).isNull()
    }

    @Test
    fun `취소 시 환불이 성공하면 Payment가 REFUNDED로 바뀐다`() {
        // given
        val reservation = 결제완료_예약_준비()
        every { paymentGateway.refund(any(), any(), any()) } returns PaymentGatewayResult.Approved("FAKE-REFUND-2")

        // when
        val result = cancelService.cancel(reservation.reservationNo!!, PHONE)

        // then
        assertThat(result.payment.status).isEqualTo(PaymentStatus.REFUNDED)
    }

    @Test
    fun `환불 요청에 원 거래 id와 결제 금액과 예약 키에서 유도한 환불 키를 넘긴다`() {
        // given
        val reservation = 결제완료_예약_준비()
        every { paymentGateway.refund(any(), any(), any()) } returns PaymentGatewayResult.Approved("FAKE-REFUND-7")

        // when
        cancelService.cancel(reservation.reservationNo!!, PHONE)

        // then
        verify(exactly = 1) {
            paymentGateway.refund("PG-TXN-ORIGINAL", 200_000, RefundIdempotencyKey.of(reservation.idempotencyKey, 0))
        }
    }

    @Test
    fun `환불이 실패해도 예약 취소 자체는 확정된다`() {
        // given
        val reservation = 결제완료_예약_준비()
        every { paymentGateway.refund(any(), any(), any()) } returns PaymentGatewayResult.Declined("환불 한도 초과")

        // when
        val result = cancelService.cancel(reservation.reservationNo!!, PHONE)

        // then: 예외 없이 끝나고, 결제는 CANCELED에 머무름(REFUNDED 아님). 거절 사유는 WARN으로 남는다
        assertThat(result.payment.status).isEqualTo(PaymentStatus.CANCELED)
        val updated = requireNotNull(reservationRepository.findById(reservation.id))
        assertThat(updated.status).isEqualTo(ReservationStatus.CANCELED)
        assertThat(logs.list.single { it.level == Level.WARN }.formattedMessage).contains("환불 한도 초과")
        // 이 실패도 시도로 세어야 스케줄러의 첫 재시도가 실패했던 같은 키로 나가 저장된 에러를 재생받지 않는다
        assertThat(result.payment.refundAttemptCount).isEqualTo(1)
        val failure = paymentHistoryRepository.findAllByPaymentId(result.payment.id).single { it.reason?.contains("환불 한도 초과") == true }
        assertThat(failure.fromStatus).isEqualTo(PaymentStatus.CANCELED)
        assertThat(failure.toStatus).isEqualTo(PaymentStatus.CANCELED)
    }

    @Test
    fun `환불 호출이 예외를 던져도 취소는 확정된다`() {
        // given
        val reservation = 결제완료_예약_준비()
        every { paymentGateway.refund(any(), any(), any()) } throws RuntimeException("네트워크 오류")

        // when
        val result = cancelService.cancel(reservation.reservationNo!!, PHONE)

        // then: 예외가 밖으로 안 새고, 취소/결제 상태는 그대로 확정됨. 원인은 ERROR와 스택트레이스로 남는다
        assertThat(result.payment.status).isEqualTo(PaymentStatus.CANCELED)
        val updated = requireNotNull(reservationRepository.findById(reservation.id))
        assertThat(updated.status).isEqualTo(ReservationStatus.CANCELED)
        assertThat(logs.list.single { it.level == Level.ERROR }.throwableProxy).isNotNull()
        assertThat(result.payment.refundAttemptCount).isEqualTo(1)
    }

    @Test
    fun `환불이 아직 처리 중이라는 충돌이 와도 취소는 확정되고 결제는 CANCELED로 남는다`() {
        // given
        val reservation = 결제완료_예약_준비()
        every { paymentGateway.refund(any(), any(), any()) } throws PaymentConflictException()

        // when
        val result = cancelService.cancel(reservation.reservationNo!!, PHONE)

        // then: 환불 재시도 대상으로 CANCELED를 유지하고 예약 취소는 확정됨. 정상 경합이라 WARN 한 줄만 남는다
        assertThat(result.payment.status).isEqualTo(PaymentStatus.CANCELED)
        val updated = requireNotNull(reservationRepository.findById(reservation.id))
        assertThat(updated.status).isEqualTo(ReservationStatus.CANCELED)
        assertThat(logs.list.single { it.level == Level.WARN }.throwableProxy).isNull()
        assertThat(logs.list.filter { it.level == Level.ERROR }).isEmpty()
        // 처리 중(409)은 실패가 아니라서 시도로 세지 않고 같은 키로 다시 확인한다
        assertThat(result.payment.refundAttemptCount).isEqualTo(0)
    }

    @Test
    fun `이미 CANCELED인 예약을 다시 취소하면 거부된다`() {
        // given
        val reservation = 결제완료_예약_준비()
        every { paymentGateway.refund(any(), any(), any()) } returns PaymentGatewayResult.Approved("FAKE-REFUND-3")
        cancelService.cancel(reservation.reservationNo!!, PHONE)

        // when & then
        assertThatThrownBy { cancelService.cancel(reservation.reservationNo!!, PHONE) }
            .isInstanceOf(ReservationAlreadyCanceledException::class.java)
    }

    @Test
    fun `reservationNo가 없으면 실패한다`() {
        // when & then
        assertThatThrownBy { cancelService.cancel("NOTEXIST0001", PHONE) }
            .isInstanceOf(ReservationLookupFailedException::class.java)
    }

    @Test
    fun `phone이 틀리면 실패한다`() {
        // given
        val reservation = 결제완료_예약_준비()

        // when & then
        assertThatThrownBy { cancelService.cancel(reservation.reservationNo!!, "01099998888") }
            .isInstanceOf(ReservationLookupFailedException::class.java)
    }

    @Test
    fun `payment_history에 SUCCESS에서 CANCELED로의 전이가 기록된다`() {
        // given
        val reservation = 결제완료_예약_준비()
        every { paymentGateway.refund(any(), any(), any()) } returns PaymentGatewayResult.Declined("환불 한도 초과")

        // when
        val result = cancelService.cancel(reservation.reservationNo!!, PHONE)

        // then
        val history = paymentHistoryRepository.findAllByPaymentId(result.payment.id)
        assertThat(history).extracting("toStatus").contains(PaymentStatus.CANCELED)
    }

    @Test
    fun `환불 성공 시 payment_history에 CANCELED에서 REFUNDED로의 전이도 기록된다`() {
        // given
        val reservation = 결제완료_예약_준비()
        every { paymentGateway.refund(any(), any(), any()) } returns PaymentGatewayResult.Approved("FAKE-REFUND-5")

        // when
        val result = cancelService.cancel(reservation.reservationNo!!, PHONE)

        // then
        val history = paymentHistoryRepository.findAllByPaymentId(result.payment.id)
        assertThat(history).extracting("toStatus").containsExactlyInAnyOrder(PaymentStatus.CANCELED, PaymentStatus.REFUNDED)
    }

    // JdbcTemplate은 진행 중인 스프링 트랜잭션의 커넥션을 같이 써서 커밋 안 된 변경도 읽는다. DataSource에서 새 커넥션을 직접 얻어야 커밋된 값만 보인다
    private fun committedStatus(
        sql: String,
        id: Long,
    ): String? = dataSource.connection.use { queryFirstString(it, sql, id) }

    private fun queryFirstString(
        connection: Connection,
        sql: String,
        id: Long,
    ): String? {
        val statement = connection.prepareStatement(sql)
        statement.setLong(1, id)
        return statement.use { it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null } }
    }

    private fun 결제완료_예약_준비(): Reservation {
        val event = eventRepository.공연_하나_저장()
        val grade = gradeRepository.등급_하나_저장(event)
        val phoneHash = phoneHasher.hash(PHONE)
        val reservation = 결제완료_예약_저장(event.id, phoneHash)
        결제완료_좌석_저장(event.id, grade.id, reservation.id, phoneHash)
        결제완료_결제_저장(reservation.id)
        return reservation
    }

    private fun 결제완료_예약_저장(
        eventId: Long,
        phoneHash: PhoneHash,
    ): Reservation =
        reservationRepository.save(
            Reservation(
                eventId = eventId,
                phoneHash = phoneHash,
                quantity = 1,
                amount = 200_000,
                holdToken = UUID.randomUUID(),
                idempotencyKey = UUID.randomUUID(),
                status = ReservationStatus.PAID,
                reservationNo = "CANCEL" + System.nanoTime().toString().takeLast(6),
            ),
        )

    private fun 결제완료_좌석_저장(
        eventId: Long,
        gradeId: Long,
        reservationId: Long,
        phoneHash: PhoneHash,
    ): Seat =
        seatRepository.save(
            Seat(
                eventId = eventId,
                gradeId = gradeId,
                section = "A",
                rowLabel = "1",
                seatNo = 1,
                ordinal = 0,
                status = SeatStatus.SOLD,
                reservationId = reservationId,
                phoneHash = phoneHash,
                slotNo = 1,
            ),
        )

    private fun 결제완료_결제_저장(reservationId: Long): Payment {
        val payment = Payment(reservationId = reservationId, amount = 200_000)
        payment.markSuccess("PG-TXN-ORIGINAL", LocalDateTime.of(2026, 1, 1, 0, 0))
        return paymentRepository.save(payment)
    }

    companion object {
        private const val PHONE = "01011112222"
    }
}
