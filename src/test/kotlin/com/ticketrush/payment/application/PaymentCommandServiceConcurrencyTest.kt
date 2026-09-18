package com.ticketrush.payment.application

import com.ticketrush.event.domain.EventRepositoryPort
import com.ticketrush.payment.domain.PaymentConfirmationResult
import com.ticketrush.payment.domain.PaymentConflictException
import com.ticketrush.payment.domain.PaymentGatewayPort
import com.ticketrush.payment.domain.PaymentGatewayResult
import com.ticketrush.payment.domain.PaymentRepositoryPort
import com.ticketrush.reservation.domain.GradeRepositoryPort
import com.ticketrush.reservation.domain.Reservation
import com.ticketrush.reservation.domain.ReservationRepositoryPort
import com.ticketrush.reservation.domain.SeatRepositoryPort
import com.ticketrush.shared.PhoneHash
import com.ticketrush.support.IntegrationTest
import com.ticketrush.support.공연_하나_저장
import com.ticketrush.support.등급_하나_저장
import com.ticketrush.support.예약_하나_저장
import com.ticketrush.support.홀드된_좌석_하나_저장
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// 정확히 동시에 들어온 요청 중 진 쪽은 PaymentConflictException(409)을
// 받고, 재시도하면 멱등 체크가 이긴 쪽 결과로 수렴시킨다. Redis 사전 차단 없이 DB 낙관적
// 락만으로 이 성질이 실제로 성립하는지 확인한다.
@Tag("concurrency")
@Import(PaymentCommandServiceConcurrencyTest.MockConfig::class)
class PaymentCommandServiceConcurrencyTest : IntegrationTest() {
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

    @RepeatedTest(5)
    fun `정확히 동시에 결제 확정이 두 번 들어오면 하나만 성공하고 재시도하면 그 결과로 수렴한다`() {
        // given
        val reservation = 홀드된_예약_준비()
        every { reservationNoGenerator.generate() } answers { "RESNO" + System.nanoTime().toString().takeLast(7) }
        every { paymentGateway.charge(any(), any(), any()) } returns PaymentGatewayResult.Approved("PG-TXN-CONC")

        // when
        val results = 동시_결제_확정_시도(reservation, threadCount = 2)

        // then
        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        val failure = results.mapNotNull { it.exceptionOrNull() }.single()
        assertThat(failure).isInstanceOf(PaymentConflictException::class.java)

        val winner = results.first { it.isSuccess }.getOrThrow()
        val retried = paymentCommandService.confirmPayment(reservation.id, reservation.holdToken)
        assertThat(retried.reservationNo).isEqualTo(winner.reservationNo)
        assertThat(retried.payment.pgTransactionId).isEqualTo(winner.payment.pgTransactionId)
    }

    private fun 동시_결제_확정_시도(
        reservation: Reservation,
        threadCount: Int,
    ): List<Result<PaymentConfirmationResult>> {
        val startGate = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)
        val results = Collections.synchronizedList(mutableListOf<Result<PaymentConfirmationResult>>())

        try {
            repeat(threadCount) {
                executor.submit {
                    startGate.await()
                    results.add(runCatching { paymentCommandService.confirmPayment(reservation.id, reservation.holdToken) })
                    doneLatch.countDown()
                }
            }
            startGate.countDown()
            check(doneLatch.await(10, TimeUnit.SECONDS)) { "결제 확정 스레드가 10초 안에 끝나지 않았다" }
        } finally {
            executor.shutdownNow()
        }
        return results
    }

    private fun 홀드된_예약_준비(): Reservation {
        val event = eventRepository.공연_하나_저장()
        val grade = gradeRepository.등급_하나_저장(event)
        val phoneHash = PhoneHash(ByteArray(32) { 1 })
        val reservation = reservationRepository.예약_하나_저장(event, phoneHash = phoneHash)
        seatRepository.홀드된_좌석_하나_저장(event, grade, reservationId = reservation.id, phoneHash = phoneHash, slotNo = 1)
        return reservation
    }
}
