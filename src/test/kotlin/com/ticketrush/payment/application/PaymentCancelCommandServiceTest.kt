package com.ticketrush.payment.application

import com.ticketrush.event.domain.EventRepositoryPort
import com.ticketrush.payment.domain.Payment
import com.ticketrush.payment.domain.PaymentGatewayPort
import com.ticketrush.payment.domain.PaymentGatewayResult
import com.ticketrush.payment.domain.PaymentHistoryRepositoryPort
import com.ticketrush.payment.domain.PaymentRepositoryPort
import com.ticketrush.payment.domain.PaymentStatus
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

    @TestConfiguration
    class MockConfig {
        @Bean
        @Primary
        fun paymentGateway(): PaymentGatewayPort = mockk()
    }

    @Test
    fun `PAID 상태의 예약을 취소하면 CANCELED로 바뀌고 좌석이 AVAILABLE로 돌아간다`() {
        // given
        val reservation = 결제완료_예약_준비()
        every { paymentGateway.refund(any(), any()) } returns PaymentGatewayResult.Approved("FAKE-REFUND-1")

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
        every { paymentGateway.refund(any(), any()) } returns PaymentGatewayResult.Approved("FAKE-REFUND-2")

        // when
        val result = cancelService.cancel(reservation.reservationNo!!, PHONE)

        // then
        assertThat(result.payment.status).isEqualTo(PaymentStatus.REFUNDED)
    }

    @Test
    fun `환불이 실패해도 예약 취소 자체는 확정된다`() {
        // given
        val reservation = 결제완료_예약_준비()
        every { paymentGateway.refund(any(), any()) } returns PaymentGatewayResult.Declined("환불 한도 초과")

        // when
        val result = cancelService.cancel(reservation.reservationNo!!, PHONE)

        // then: 예외 없이 끝나고, 결제는 CANCELED에 머무름(REFUNDED 아님)
        assertThat(result.payment.status).isEqualTo(PaymentStatus.CANCELED)
        val updated = requireNotNull(reservationRepository.findById(reservation.id))
        assertThat(updated.status).isEqualTo(ReservationStatus.CANCELED)
    }

    @Test
    fun `이미 CANCELED인 예약을 다시 취소하면 거부된다`() {
        // given
        val reservation = 결제완료_예약_준비()
        every { paymentGateway.refund(any(), any()) } returns PaymentGatewayResult.Approved("FAKE-REFUND-3")
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
        every { paymentGateway.refund(any(), any()) } returns PaymentGatewayResult.Declined("환불 한도 초과")

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
        every { paymentGateway.refund(any(), any()) } returns PaymentGatewayResult.Approved("FAKE-REFUND-5")

        // when
        val result = cancelService.cancel(reservation.reservationNo!!, PHONE)

        // then
        val history = paymentHistoryRepository.findAllByPaymentId(result.payment.id)
        assertThat(history).extracting("toStatus").containsExactlyInAnyOrder(PaymentStatus.CANCELED, PaymentStatus.REFUNDED)
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
