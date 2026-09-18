package com.ticketrush.payment.application

import com.ticketrush.event.domain.EventRepositoryPort
import com.ticketrush.payment.domain.PaymentDeclinedException
import com.ticketrush.payment.domain.PaymentGatewayPort
import com.ticketrush.payment.domain.PaymentGatewayResult
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
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import java.time.LocalDateTime
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
    lateinit var paymentGateway: PaymentGatewayPort

    @Autowired
    lateinit var reservationNoGenerator: ReservationNoGenerator

    @Autowired
    lateinit var paymentCommandService: PaymentCommandService

    @TestConfiguration
    class MockConfig {
        @Bean
        @Primary
        fun paymentGateway(): PaymentGatewayPort = mockk()

        @Bean
        @Primary
        fun reservationNoGenerator(): ReservationNoGenerator = mockk()
    }

    @Test
    fun `HOLDING 상태의 예약에 결제를 확정하면 성공하고 예매번호가 발급된다`() {
        // given
        val reservation = 홀드된_예약_준비()
        every { reservationNoGenerator.generate() } returns "RESNO000001"
        every {
            paymentGateway.charge(reservation.id, reservation.amount, reservation.idempotencyKey)
        } returns PaymentGatewayResult.Approved(pgTransactionId = "PG-TXN-1")

        // when
        val result = paymentCommandService.confirmPayment(reservation.id, reservation.holdToken)

        // then
        assertThat(result.reservationNo).isEqualTo("RESNO000001")
        assertThat(result.payment.status).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(result.payment.pgTransactionId).isEqualTo("PG-TXN-1")
    }

    @Test
    fun `결제 성공 시 좌석이 SOLD로 전환되고 홀드 만료 시각이 비워진다`() {
        // given
        val reservation = 홀드된_예약_준비()
        every { reservationNoGenerator.generate() } returns "RESNO000002"
        every { paymentGateway.charge(any(), any(), any()) } returns PaymentGatewayResult.Approved("PG-TXN-2")

        // when
        paymentCommandService.confirmPayment(reservation.id, reservation.holdToken)

        // then
        val seat = seatRepository.findAllByEventId(reservation.eventId).single()
        assertThat(seat.status).isEqualTo(SeatStatus.SOLD)
        assertThat(seat.holdExpiresAt).isNull()
    }

    @Test
    fun `존재하지 않는 예약이면 ReservationNotFoundException이 발생한다`() {
        // when & then
        assertThatThrownBy { paymentCommandService.confirmPayment(999_999L, UUID.randomUUID()) }
            .isInstanceOf(ReservationNotFoundException::class.java)
    }

    @Test
    fun `holdToken이 일치하지 않으면 HoldTokenMismatchException이 발생한다`() {
        // given
        val reservation = 홀드된_예약_준비()

        // when & then
        assertThatThrownBy { paymentCommandService.confirmPayment(reservation.id, UUID.randomUUID()) }
            .isInstanceOf(HoldTokenMismatchException::class.java)
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
        assertThatThrownBy { paymentCommandService.confirmPayment(reservation.id, reservation.holdToken) }
            .isInstanceOf(ReservationNotHoldingException::class.java)
    }

    @Test
    fun `홀드 시각은 지났지만 status가 아직 HOLDING인 예약은 결제를 거부한다`() {
        // given: 스위퍼가 아직 못 훑은 상황을 흉내냄 - status는 HOLDING인데 holdExpiresAt은 과거
        val reservation = 홀드된_예약_준비(holdExpiresAt = LocalDateTime.of(2020, 1, 1, 0, 0))

        // when & then
        assertThatThrownBy { paymentCommandService.confirmPayment(reservation.id, reservation.holdToken) }
            .isInstanceOf(ReservationNotHoldingException::class.java)
    }

    @Test
    fun `이미 결제된 예약에 다시 요청하면 PG를 재호출하지 않고 기존 결과를 반환한다`() {
        // given
        val reservation = 홀드된_예약_준비()
        every { reservationNoGenerator.generate() } returns "RESNO000003"
        every { paymentGateway.charge(any(), any(), any()) } returns PaymentGatewayResult.Approved("PG-TXN-3")
        paymentCommandService.confirmPayment(reservation.id, reservation.holdToken)

        // when
        val result = paymentCommandService.confirmPayment(reservation.id, reservation.holdToken)

        // then
        assertThat(result.payment.pgTransactionId).isEqualTo("PG-TXN-3")
        verify(exactly = 1) { paymentGateway.charge(any(), any(), any()) }
    }

    @Test
    fun `PG가 거절하면 PaymentDeclinedException이 발생한다`() {
        // given
        val reservation = 홀드된_예약_준비()
        every { paymentGateway.charge(any(), any(), any()) } returns PaymentGatewayResult.Declined("한도 초과")

        // when & then
        assertThatThrownBy { paymentCommandService.confirmPayment(reservation.id, reservation.holdToken) }
            .isInstanceOf(PaymentDeclinedException::class.java)
    }

    @Test
    fun `PG 거절 시 예약과 좌석의 홀드 만료 시각이 똑같이 단축된다`() {
        // given
        val reservation = 홀드된_예약_준비(holdExpiresAt = FAR_FUTURE)
        every { paymentGateway.charge(any(), any(), any()) } returns PaymentGatewayResult.Declined("한도 초과")

        // when
        runCatching { paymentCommandService.confirmPayment(reservation.id, reservation.holdToken) }

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
        every { paymentGateway.charge(any(), any(), any()) } returns PaymentGatewayResult.Declined("한도 초과")

        // when
        runCatching { paymentCommandService.confirmPayment(reservation.id, reservation.holdToken) }

        // then
        val payment = paymentRepository.findByReservationId(reservation.id)
        assertThat(payment?.status).isEqualTo(PaymentStatus.FAILED)
    }

    @Test
    fun `실패했던 결제를 재시도해서 승인되면 같은 Payment 행이 SUCCESS로 갱신된다`() {
        // given
        val reservation = 홀드된_예약_준비()
        every { paymentGateway.charge(any(), any(), any()) } returns PaymentGatewayResult.Declined("한도 초과")
        runCatching { paymentCommandService.confirmPayment(reservation.id, reservation.holdToken) }
        val failedPaymentId = requireNotNull(paymentRepository.findByReservationId(reservation.id)).id

        // when
        every { reservationNoGenerator.generate() } returns "RESNO000004"
        every { paymentGateway.charge(any(), any(), any()) } returns PaymentGatewayResult.Approved("PG-TXN-4")
        val result = paymentCommandService.confirmPayment(reservation.id, reservation.holdToken)

        // then
        assertThat(result.payment.id).isEqualTo(failedPaymentId)
        assertThat(result.payment.status).isEqualTo(PaymentStatus.SUCCESS)
    }

    @Test
    fun `예매번호가 기존 값과 충돌하면 재시도해서 다른 번호로 저장에 성공한다`() {
        // given: "COLLIDE00001"을 이미 쓰고 있는 다른 예약
        val other = 홀드된_예약_준비()
        every { reservationNoGenerator.generate() } returns "COLLIDE00001"
        every { paymentGateway.charge(other.id, any(), any()) } returns PaymentGatewayResult.Approved("PG-TXN-OTHER")
        paymentCommandService.confirmPayment(other.id, other.holdToken)

        val reservation = 홀드된_예약_준비()
        every { reservationNoGenerator.generate() } returnsMany listOf("COLLIDE00001", "UNIQUE000001")
        every { paymentGateway.charge(reservation.id, any(), any()) } returns PaymentGatewayResult.Approved("PG-TXN-2")

        // when
        val result = paymentCommandService.confirmPayment(reservation.id, reservation.holdToken)

        // then
        assertThat(result.reservationNo).isEqualTo("UNIQUE000001")
        verify(exactly = 1) { paymentGateway.charge(reservation.id, any(), any()) }
    }

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
