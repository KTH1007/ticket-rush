package com.ticketrush.payment.application

import com.ticketrush.event.domain.EventRepositoryPort
import com.ticketrush.payment.domain.PaymentConfirmationResult
import com.ticketrush.payment.domain.PaymentConflictException
import com.ticketrush.payment.domain.PaymentDeclinedException
import com.ticketrush.payment.domain.PaymentGatewayPort
import com.ticketrush.payment.domain.PaymentGatewayResult
import com.ticketrush.payment.domain.PaymentHistoryRepositoryPort
import com.ticketrush.payment.domain.PaymentInquiryResult
import com.ticketrush.payment.domain.PaymentOrderMismatchException
import com.ticketrush.payment.domain.PaymentRepositoryPort
import com.ticketrush.payment.domain.PaymentStatus
import com.ticketrush.reservation.domain.GradeRepositoryPort
import com.ticketrush.reservation.domain.HoldTokenMismatchException
import com.ticketrush.reservation.domain.Reservation
import com.ticketrush.reservation.domain.ReservationNotFoundException
import com.ticketrush.reservation.domain.ReservationNotHoldingException
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
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test

@Import(PaymentCommandServiceTest.MockConfig::class)
class PaymentCommandServiceTest : IntegrationTest() {
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
    lateinit var reservationNoGenerator: ReservationNoGenerator

    @Autowired
    lateinit var paymentCommandService: PaymentCommandService

    @Autowired
    lateinit var clock: Clock

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @TestConfiguration
    class MockConfig {
        @Bean
        @Primary
        fun paymentGateway(): PaymentGatewayPort = mockk()

        @Bean
        @Primary
        fun reservationNoGenerator(): ReservationNoGenerator = mockk()
    }

    @Autowired
    lateinit var outboxRepository: com.ticketrush.outbox.domain.OutboxRepositoryPort

    // 모의 게이트웨이는 컨텍스트 안에서 공유돼 앞선 테스트의 호출 기록과 넓은 스텁이 남는다
    @BeforeEach
    fun resetGatewayMock() {
        clearMocks(paymentGateway)
    }

    @Test
    fun `결제를 확정하면 outbox에 RESERVATION_PAID 이벤트가 같이 커밋된다`() {
        // given
        val reservation = 홀드된_예약_준비()
        every { reservationNoGenerator.generate() } returns "RESNO000099"
        every {
            paymentGateway.charge(any(), any(), any(), any())
        } returns PaymentGatewayResult.Approved(pgTransactionId = "PG-TXN-99")

        // when
        paymentCommandService.결제_확정(reservation)

        // then
        val recorded = outboxRepository.findByAggregateId(reservation.id).singleOrNull()
        assertThat(recorded).isNotNull
        assertThat(recorded!!.eventType).isEqualTo("RESERVATION_PAID")
    }

    @Test
    fun `HOLDING 상태의 예약에 결제를 확정하면 성공하고 예매번호가 발급된다`() {
        // given
        val reservation = 홀드된_예약_준비()
        every { reservationNoGenerator.generate() } returns "RESNO000001"
        every {
            paymentGateway.charge(any(), any(), any(), any())
        } returns PaymentGatewayResult.Approved(pgTransactionId = "PG-TXN-1")

        // when
        val result = paymentCommandService.결제_확정(reservation)

        // then
        assertThat(result.reservationNo).isEqualTo("RESNO000001")
        assertThat(result.payment.status).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(result.payment.pgTransactionId).isEqualTo("PG-TXN-1")
    }

    @Test
    fun `PG가 승인 시각을 주면 Payment의 paidAt은 서버 시각이 아니라 그 승인 시각이다`() {
        // given
        val reservation = 홀드된_예약_준비()
        val approvedAt = LocalDateTime.of(2020, 1, 1, 10, 15, 30)
        every { reservationNoGenerator.generate() } returns "RESNO000020"
        every {
            paymentGateway.charge(any(), any(), any(), any())
        } returns PaymentGatewayResult.Approved(pgTransactionId = "PG-TXN-20", approvedAt = approvedAt)

        // when
        val result = paymentCommandService.결제_확정(reservation)

        // then
        assertThat(result.payment.paidAt).isEqualTo(approvedAt)
        assertThat(paymentRepository.findByReservationId(reservation.id)?.paidAt).isEqualTo(approvedAt)
    }

    @Test
    fun `PG가 승인 시각을 주지 않으면 Payment의 paidAt은 서버 시각이다`() {
        // given
        val reservation = 홀드된_예약_준비()
        every { reservationNoGenerator.generate() } returns "RESNO000021"
        every {
            paymentGateway.charge(any(), any(), any(), any())
        } returns PaymentGatewayResult.Approved(pgTransactionId = "PG-TXN-21", approvedAt = null)
        val before = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS)

        // when
        paymentCommandService.결제_확정(reservation)

        // then
        val paidAt = requireNotNull(paymentRepository.findByReservationId(reservation.id)?.paidAt)
        assertThat(paidAt).isBetween(before, LocalDateTime.now(clock))
    }

    @Test
    fun `결제 성공 시 좌석이 SOLD로 전환되고 홀드 만료 시각이 비워진다`() {
        // given
        val reservation = 홀드된_예약_준비()
        every { reservationNoGenerator.generate() } returns "RESNO000002"
        every { paymentGateway.charge(any(), any(), any(), any()) } returns PaymentGatewayResult.Approved("PG-TXN-2")

        // when
        paymentCommandService.결제_확정(reservation)

        // then
        val seat = seatRepository.findAllByEventId(reservation.eventId).single()
        assertThat(seat.status).isEqualTo(SeatStatus.SOLD)
        assertThat(seat.holdExpiresAt).isNull()
    }

    @Test
    fun `존재하지 않는 예약이면 ReservationNotFoundException이 발생한다`() {
        // when & then
        assertThatThrownBy {
            paymentCommandService.confirmPayment(
                reservationId = 999_999L,
                holdToken = UUID.randomUUID(),
                paymentKey = "test-payment-key",
                orderId = "order-id",
                amount = 100_000,
            )
        }.isInstanceOf(ReservationNotFoundException::class.java)
    }

    @Test
    fun `holdToken이 일치하지 않으면 HoldTokenMismatchException이 발생한다`() {
        // given
        val reservation = 홀드된_예약_준비()

        // when & then
        assertThatThrownBy {
            paymentCommandService.confirmPayment(
                reservationId = reservation.id,
                holdToken = UUID.randomUUID(),
                paymentKey = "test-payment-key",
                orderId = reservation.idempotencyKey.toString(),
                amount = reservation.amount,
            )
        }.isInstanceOf(HoldTokenMismatchException::class.java)
    }

    @Test
    fun `HOLDING이 아닌 예약이면 ReservationNotHoldingException이 발생한다`() {
        // given
        val event = eventRepository.공연_하나_저장()
        val reservation =
            reservationRepository.save(
                Reservation(
                    eventId = event.id,
                    phoneHash = PhoneHash(ByteArray(32) { 1 }),
                    quantity = 1,
                    amount = 100_000,
                    holdToken = UUID.randomUUID(),
                    idempotencyKey = UUID.randomUUID(),
                    status = ReservationStatus.EXPIRED,
                ),
            )

        // when & then
        assertThatThrownBy { paymentCommandService.결제_확정(reservation) }
            .isInstanceOf(ReservationNotHoldingException::class.java)
    }

    // PG 호출은 최대 6~12초가 걸릴 수 있어, 남은 홀드가 그보다 짧으면 호출 중에 만료돼 청구만 되고 티켓이 없는 상태가 될 수 있다
    @Test
    fun `남은 홀드 시간이 승인에 필요한 시간보다 짧으면 PG를 부르지 않고 결제를 거부한다`() {
        // given
        val reservation = 홀드된_예약_준비(holdExpiresAt = LocalDateTime.now(clock).plusSeconds(5))

        // when & then
        assertThatThrownBy { paymentCommandService.결제_확정(reservation, paymentKey = "near-expiry-payment-key") }
            .isInstanceOf(ReservationNotHoldingException::class.java)
        verify(exactly = 0) { paymentGateway.charge("near-expiry-payment-key", any(), any(), any()) }
    }

    @Test
    fun `홀드 시각은 지났지만 status가 아직 HOLDING인 예약은 결제를 거부한다`() {
        // given: 스위퍼가 아직 못 훑은 상황을 흉내냄 - status는 HOLDING인데 holdExpiresAt은 과거
        val reservation = 홀드된_예약_준비(holdExpiresAt = LocalDateTime.of(2020, 1, 1, 0, 0))

        // when & then
        assertThatThrownBy { paymentCommandService.결제_확정(reservation) }
            .isInstanceOf(ReservationNotHoldingException::class.java)
    }

    @Test
    fun `요청의 orderId가 예약의 idempotencyKey와 다르면 예외가 난다`() {
        val event = eventRepository.공연_하나_저장()
        val reservation = reservationRepository.예약_하나_저장(event = event)

        assertThatThrownBy {
            paymentCommandService.confirmPayment(
                reservationId = reservation.id,
                holdToken = reservation.holdToken,
                paymentKey = "test-payment-key",
                orderId = "잘못된-orderId",
                amount = reservation.amount,
            )
        }.isInstanceOf(PaymentOrderMismatchException::class.java)
    }

    @Test
    fun `요청의 amount가 예약 금액과 다르면 예외가 난다`() {
        val event = eventRepository.공연_하나_저장()
        val reservation = reservationRepository.예약_하나_저장(event = event, amount = 100_000)

        assertThatThrownBy {
            paymentCommandService.confirmPayment(
                reservationId = reservation.id,
                holdToken = reservation.holdToken,
                paymentKey = "test-payment-key",
                orderId = reservation.idempotencyKey.toString(),
                amount = 1,
            )
        }.isInstanceOf(PaymentOrderMismatchException::class.java)
    }

    @Test
    fun `이미 결제된 예약에 다시 요청하면 PG를 재호출하지 않고 기존 결과를 반환한다`() {
        // given
        val reservation = 홀드된_예약_준비()
        every { reservationNoGenerator.generate() } returns "RESNO000003"
        every { paymentGateway.charge(any(), any(), any(), any()) } returns PaymentGatewayResult.Approved("PG-TXN-3")
        paymentCommandService.결제_확정(reservation)

        // when
        val result = paymentCommandService.결제_확정(reservation)

        // then
        assertThat(result.payment.pgTransactionId).isEqualTo("PG-TXN-3")
        verify(exactly = 1) { paymentGateway.charge(any(), any(), any(), any()) }
    }

    @Test
    fun `PG가 거절하면 PaymentDeclinedException이 발생한다`() {
        // given
        val reservation = 홀드된_예약_준비()
        every { paymentGateway.charge(any(), any(), any(), any()) } returns PaymentGatewayResult.Declined("한도 초과")

        // when & then
        assertThatThrownBy { paymentCommandService.결제_확정(reservation) }
            .isInstanceOf(PaymentDeclinedException::class.java)
    }

    @Test
    fun `PG 거절 시 예약과 좌석의 홀드 만료 시각이 똑같이 단축된다`() {
        // given
        val reservation = 홀드된_예약_준비(holdExpiresAt = FAR_FUTURE)
        every { paymentGateway.charge(any(), any(), any(), any()) } returns PaymentGatewayResult.Declined("한도 초과")

        // when
        runCatching { paymentCommandService.결제_확정(reservation) }

        // then
        val updated = requireNotNull(reservationRepository.findById(reservation.id))
        assertThat(updated.holdExpiresAt).isBefore(FAR_FUTURE)
        val seat = seatRepository.findAllByEventId(reservation.eventId).single()
        assertThat(seat.holdExpiresAt).isEqualTo(updated.holdExpiresAt)
    }

    @Test
    fun `PG 거절 시 Payment가 FAILED로 기록된다`() {
        // given
        val reservation = 홀드된_예약_준비()
        every { paymentGateway.charge(any(), any(), any(), any()) } returns PaymentGatewayResult.Declined("한도 초과")

        // when
        runCatching { paymentCommandService.결제_확정(reservation) }

        // then
        val payment = paymentRepository.findByReservationId(reservation.id)
        assertThat(payment?.status).isEqualTo(PaymentStatus.FAILED)
    }

    @Test
    fun `실패했던 결제를 재시도해서 승인되면 같은 Payment 행이 SUCCESS로 갱신된다`() {
        // given
        val reservation = 홀드된_예약_준비()
        every { paymentGateway.charge(any(), any(), any(), any()) } returns PaymentGatewayResult.Declined("한도 초과")
        runCatching { paymentCommandService.결제_확정(reservation) }
        val failedPaymentId = requireNotNull(paymentRepository.findByReservationId(reservation.id)).id

        // when
        every { reservationNoGenerator.generate() } returns "RESNO000004"
        every { paymentGateway.charge(any(), any(), any(), any()) } returns PaymentGatewayResult.Approved("PG-TXN-4")
        val result = paymentCommandService.결제_확정(reservation)

        // then
        assertThat(result.payment.id).isEqualTo(failedPaymentId)
        assertThat(result.payment.status).isEqualTo(PaymentStatus.SUCCESS)
    }

    @Test
    fun `Toss가 같은 멱등키로 거절을 재생해도 거절 뒤 재시도는 새 키로 요청해서 승인된다`() {
        // given: 실제 Toss처럼 키별로 첫 응답을 저장해 두고 같은 키면 그 응답을 그대로 돌려준다(실제 샌드박스에서 확인한 동작)
        val reservation = 홀드된_예약_준비()
        val storedByKey = mutableMapOf<UUID, PaymentGatewayResult>()
        every { paymentGateway.charge(any(), any(), any(), any()) } answers {
            storedByKey.getOrPut(arg<UUID>(3)) {
                when (arg<String>(0)) {
                    "declined-payment-key" -> PaymentGatewayResult.Declined("카드 거절")
                    else -> PaymentGatewayResult.Approved("PG-TXN-9")
                }
            }
        }
        every { reservationNoGenerator.generate() } returns "RESNO000009"
        runCatching { paymentCommandService.결제_확정(reservation, paymentKey = "declined-payment-key") }

        // when: 같은 예약으로 새 결제 인증(paymentKey)을 받아 재시도한다
        val result = paymentCommandService.결제_확정(reservation, paymentKey = "retried-payment-key")

        // then
        assertThat(result.payment.status).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(storedByKey.keys).hasSize(2)
    }

    @Test
    fun `예매번호가 기존 값과 충돌하면 재시도해서 다른 번호로 저장에 성공한다`() {
        // given: "COLLIDE00001"을 이미 쓰고 있는 다른 예약
        val other = 홀드된_예약_준비()
        every { reservationNoGenerator.generate() } returns "COLLIDE00001"
        every { paymentGateway.charge(any(), any(), any(), other.idempotencyKey) } returns PaymentGatewayResult.Approved("PG-TXN-OTHER")
        paymentCommandService.결제_확정(other)

        val reservation = 홀드된_예약_준비()
        every { reservationNoGenerator.generate() } returnsMany listOf("COLLIDE00001", "UNIQUE000001")
        every { paymentGateway.charge(any(), any(), any(), reservation.idempotencyKey) } returns PaymentGatewayResult.Approved("PG-TXN-2")

        // when
        val result = paymentCommandService.결제_확정(reservation)

        // then
        assertThat(result.reservationNo).isEqualTo("UNIQUE000001")
        verify(exactly = 1) { paymentGateway.charge(any(), any(), any(), reservation.idempotencyKey) }
    }

    @Test
    fun `결제 성공 시 payment_history에 PENDING에서 SUCCESS로의 전이가 기록된다`() {
        // given
        val reservation = 홀드된_예약_준비()
        every { reservationNoGenerator.generate() } returns "RESNO000010"
        every { paymentGateway.charge(any(), any(), any(), any()) } returns PaymentGatewayResult.Approved("PG-TXN-10")

        // when
        val result = paymentCommandService.결제_확정(reservation)

        // then
        val history = paymentHistoryRepository.findAllByPaymentId(result.payment.id)
        assertThat(history).hasSize(1)
        assertThat(history.single().fromStatus).isEqualTo(PaymentStatus.PENDING)
        assertThat(history.single().toStatus).isEqualTo(PaymentStatus.SUCCESS)
    }

    @Test
    fun `결제 거절 시 payment_history에 PENDING에서 FAILED로의 전이가 기록되고 사유가 남는다`() {
        // given
        val reservation = 홀드된_예약_준비()
        every { paymentGateway.charge(any(), any(), any(), any()) } returns PaymentGatewayResult.Declined("한도 초과")

        // when
        runCatching { paymentCommandService.결제_확정(reservation) }

        // then
        val payment = requireNotNull(paymentRepository.findByReservationId(reservation.id))
        val history = paymentHistoryRepository.findAllByPaymentId(payment.id)
        assertThat(history).hasSize(1)
        assertThat(history.single().toStatus).isEqualTo(PaymentStatus.FAILED)
        assertThat(history.single().reason).isEqualTo("한도 초과")
    }

    @Test
    fun `PG가 PaymentConflictException을 던지면 실패로 확정하지 않고 PENDING과 홀드를 그대로 둔다`() {
        // given
        val reservation = 홀드된_예약_준비(holdExpiresAt = FAR_FUTURE)
        every { paymentGateway.charge(any(), any(), any(), any()) } throws PaymentConflictException()

        // when & then
        assertThatThrownBy { paymentCommandService.결제_확정(reservation) }
            .isInstanceOf(PaymentConflictException::class.java)

        val payment = requireNotNull(paymentRepository.findByReservationId(reservation.id))
        assertThat(payment.status).isEqualTo(PaymentStatus.PENDING)
        // 처리 중(409)은 거절이 아니라서 거절 횟수를 올리지 않고 같은 키로 다시 확인한다
        assertThat(payment.chargeAttemptCount).isEqualTo(0)
        assertThat(paymentHistoryRepository.findAllByPaymentId(payment.id)).extracting("toStatus").doesNotContain(PaymentStatus.FAILED)
        val updated = requireNotNull(reservationRepository.findById(reservation.id))
        assertThat(updated.holdExpiresAt).isEqualTo(FAR_FUTURE)
        val seat = seatRepository.findAllByEventId(reservation.eventId).single()
        assertThat(seat.holdExpiresAt).isEqualTo(FAR_FUTURE)

        // 커밋된 stale PENDING 행이 남으면 이후 컨텍스트의 회수 스케줄러가 집어가므로 정리한다
        payment.markFailed()
        paymentRepository.save(payment)
    }

    @Test
    fun `재시도로 성공하면 payment_history에 FAILED에서 PENDING으로 다시 열린 뒤 PENDING에서 SUCCESS로의 전이가 추가로 기록된다`() {
        // given
        val reservation = 홀드된_예약_준비()
        every { paymentGateway.charge(any(), any(), any(), any()) } returns PaymentGatewayResult.Declined("한도 초과")
        runCatching { paymentCommandService.결제_확정(reservation) }

        // when
        every { reservationNoGenerator.generate() } returns "RESNO000011"
        every { paymentGateway.charge(any(), any(), any(), any()) } returns PaymentGatewayResult.Approved("PG-TXN-11")
        val result = paymentCommandService.결제_확정(reservation)

        // then
        val history = paymentHistoryRepository.findAllByPaymentId(result.payment.id)
        assertThat(history).hasSize(3)
        assertThat(history)
            .extracting("toStatus")
            .containsExactlyInAnyOrder(PaymentStatus.FAILED, PaymentStatus.PENDING, PaymentStatus.SUCCESS)
    }

    // PENDING이어야 회수 스케줄러가 대사한다. FAILED로 남으면 Toss가 승인했어도 아무도 모른다
    @Test
    fun `거절 뒤 재시도가 응답 없이 예외로 끝나면 결제는 PENDING으로 남고 거절 횟수는 그대로다`() {
        // given
        val reservation = 홀드된_예약_준비()
        every { paymentGateway.charge(any(), any(), any(), any()) } returns PaymentGatewayResult.Declined("한도 초과")
        runCatching { paymentCommandService.결제_확정(reservation) }
        every { paymentGateway.charge(any(), any(), any(), any()) } throws IllegalStateException("타임아웃")

        // when
        assertThatThrownBy { paymentCommandService.결제_확정(reservation, paymentKey = "retry-payment-key") }
            .isInstanceOf(IllegalStateException::class.java)

        // then
        val payment = requireNotNull(paymentRepository.findByReservationId(reservation.id))
        assertThat(payment.status).isEqualTo(PaymentStatus.PENDING)
        assertThat(payment.tossPaymentKey).isEqualTo("retry-payment-key")
        assertThat(payment.chargeAttemptCount).isEqualTo(1)

        // 커밋된 stale PENDING 행이 남으면 이후 컨텍스트의 회수 스케줄러가 집어가므로 정리한다
        payment.markFailed()
        paymentRepository.save(payment)
    }

    // 응답을 못 받은 PENDING을 다른 paymentKey로 덮어쓰기 전에 이전 키가 이미 승인됐는지 확인해야, 승인된 결제를 놓치지 않는다
    @Test
    fun `오래된 PENDING의 이전 paymentKey가 이미 승인됐으면 새 키로 승인하지 않고 그 승인을 확정한다`() {
        // given
        val reservation = 홀드된_예약_준비()
        불확실한_PENDING_만들기(reservation, "pk-prev-done")
        every { reservationNoGenerator.generate() } returns "RESNO000031"
        val approvedAt = LocalDateTime.of(2026, 10, 7, 12, 0)
        every { paymentGateway.inquire("pk-prev-done") } returns PaymentInquiryResult.Done("pk-prev-done", approvedAt)

        // when
        val result = paymentCommandService.결제_확정(reservation, paymentKey = "pk-new-unused")

        // then
        assertThat(result.payment.status).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(result.payment.pgTransactionId).isEqualTo("pk-prev-done")
        assertThat(result.payment.paidAt).isEqualTo(approvedAt)
        verify(exactly = 0) { paymentGateway.charge("pk-new-unused", any(), any(), any()) }
        assertThat(requireNotNull(reservationRepository.findById(reservation.id)).status).isEqualTo(ReservationStatus.PAID)
    }

    @Test
    fun `오래된 PENDING의 이전 paymentKey가 승인되지 않았으면 새 키로 이어받아 승인한다`() {
        // given
        val reservation = 홀드된_예약_준비()
        불확실한_PENDING_만들기(reservation, "pk-prev-aborted")
        every { reservationNoGenerator.generate() } returns "RESNO000032"
        every { paymentGateway.inquire("pk-prev-aborted") } returns PaymentInquiryResult.NotApproved("ABORTED")
        every { paymentGateway.charge("pk-new-after-aborted", any(), any(), any()) } returns
            PaymentGatewayResult.Approved("pk-new-after-aborted")

        // when
        val result = paymentCommandService.결제_확정(reservation, paymentKey = "pk-new-after-aborted")

        // then
        assertThat(result.payment.status).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(result.payment.pgTransactionId).isEqualTo("pk-new-after-aborted")
    }

    @Test
    fun `오래된 PENDING의 이전 paymentKey가 Toss에 없으면 새 키로 이어받아 승인한다`() {
        // given
        val reservation = 홀드된_예약_준비()
        불확실한_PENDING_만들기(reservation, "pk-prev-missing")
        every { reservationNoGenerator.generate() } returns "RESNO000033"
        every { paymentGateway.inquire("pk-prev-missing") } returns PaymentInquiryResult.NotFound
        every { paymentGateway.charge("pk-new-after-missing", any(), any(), any()) } returns
            PaymentGatewayResult.Approved("pk-new-after-missing")

        // when
        val result = paymentCommandService.결제_확정(reservation, paymentKey = "pk-new-after-missing")

        // then
        assertThat(result.payment.pgTransactionId).isEqualTo("pk-new-after-missing")
    }

    @Test
    fun `이전 paymentKey 조회가 실패하면 덮어쓰지 않고 충돌로 응답하며 PENDING과 이전 키를 그대로 둔다`() {
        // given
        val reservation = 홀드된_예약_준비()
        불확실한_PENDING_만들기(reservation, "pk-prev-unknown")
        every { paymentGateway.inquire("pk-prev-unknown") } throws IllegalStateException("조회 타임아웃")

        // when & then
        assertThatThrownBy { paymentCommandService.결제_확정(reservation, paymentKey = "pk-new-not-sent") }
            .isInstanceOf(PaymentConflictException::class.java)
        verify(exactly = 0) { paymentGateway.charge("pk-new-not-sent", any(), any(), any()) }
        val payment = requireNotNull(paymentRepository.findByReservationId(reservation.id))
        assertThat(payment.status).isEqualTo(PaymentStatus.PENDING)
        assertThat(payment.tossPaymentKey).isEqualTo("pk-prev-unknown")

        // 커밋된 stale PENDING 행이 남으면 이후 컨텍스트의 회수 스케줄러가 집어가므로 정리한다
        payment.markFailed()
        paymentRepository.save(payment)
    }

    @Test
    fun `같은 paymentKey로 이어받을 때는 이전 키를 조회하지 않는다`() {
        // given
        val reservation = 홀드된_예약_준비()
        불확실한_PENDING_만들기(reservation, "pk-prev-same")
        every { reservationNoGenerator.generate() } returns "RESNO000034"
        every { paymentGateway.charge("pk-prev-same", any(), any(), any()) } returns PaymentGatewayResult.Approved("pk-prev-same")

        // when
        paymentCommandService.결제_확정(reservation, paymentKey = "pk-prev-same")

        // then
        verify(exactly = 0) { paymentGateway.inquire("pk-prev-same") }
    }

    @Test
    fun `아직 처리 중인 신선한 PENDING을 다른 paymentKey로 요청하면 이전 키를 조회하지 않고 충돌로 응답한다`() {
        // given: 갱신 시각을 미래로 밀어 테스트 속도와 무관하게 확실히 신선하게 만든다
        val reservation = 홀드된_예약_준비()
        불확실한_PENDING_만들기(reservation, "pk-prev-fresh", updatedAtShift = "+ interval '1 hour'")

        // when & then
        assertThatThrownBy { paymentCommandService.결제_확정(reservation, paymentKey = "pk-new-fresh") }
            .isInstanceOf(PaymentConflictException::class.java)
        verify(exactly = 0) { paymentGateway.inquire("pk-prev-fresh") }

        // 커밋된 PENDING 행이 남으면 이후 컨텍스트의 회수 스케줄러가 집어가므로 정리한다
        val payment = requireNotNull(paymentRepository.findByReservationId(reservation.id))
        payment.markFailed()
        paymentRepository.save(payment)
    }

    // 응답 없이 끝난 승인(타임아웃)으로 PENDING을 남긴 뒤, 갱신 시각을 옮겨 오래된(또는 신선한) 클레임으로 만든다
    private fun 불확실한_PENDING_만들기(
        reservation: Reservation,
        paymentKey: String,
        updatedAtShift: String = "- interval '1 hour'",
    ) {
        every { paymentGateway.charge(paymentKey, any(), any(), any()) } throws IllegalStateException("타임아웃")
        runCatching { paymentCommandService.결제_확정(reservation, paymentKey = paymentKey) }
        jdbcTemplate.update("UPDATE payment SET updated_at = updated_at $updatedAtShift WHERE reservation_id = ?", reservation.id)
    }

    private fun PaymentCommandService.결제_확정(
        reservation: Reservation,
        paymentKey: String = "test-payment-key",
    ): PaymentConfirmationResult =
        confirmPayment(
            reservationId = reservation.id,
            holdToken = reservation.holdToken,
            paymentKey = paymentKey,
            orderId = reservation.idempotencyKey.toString(),
            amount = reservation.amount,
        )

    private fun 홀드된_예약_준비(
        amount: Int = 100_000,
        holdExpiresAt: LocalDateTime = FAR_FUTURE,
    ): Reservation {
        val event = eventRepository.공연_하나_저장()
        val grade = gradeRepository.등급_하나_저장(event, price = amount)
        val phoneHash = PhoneHash(ByteArray(32) { 1 })
        val reservation =
            reservationRepository.예약_하나_저장(event, phoneHash = phoneHash, amount = amount, holdExpiresAt = holdExpiresAt)
        seatRepository.홀드된_좌석_하나_저장(
            event,
            grade,
            reservationId = reservation.id,
            phoneHash = phoneHash,
            slotNo = 1,
            holdExpiresAt = holdExpiresAt,
        )
        return reservation
    }

    companion object {
        private val FAR_FUTURE: LocalDateTime = LocalDateTime.of(2030, 1, 1, 0, 0)
    }
}
