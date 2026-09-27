package com.ticketrush.payment.application

import com.ticketrush.event.domain.EventRepositoryPort
import com.ticketrush.payment.PaymentPolicyProperties
import com.ticketrush.payment.domain.PaymentConflictException
import com.ticketrush.payment.domain.PaymentRepositoryPort
import com.ticketrush.payment.domain.PaymentStatus
import com.ticketrush.reservation.domain.ReservationRepositoryPort
import com.ticketrush.support.IntegrationTest
import com.ticketrush.support.공연_하나_저장
import com.ticketrush.support.예약_하나_저장
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime
import kotlin.test.Test

class PaymentClaimServiceTest : IntegrationTest() {
    @Autowired
    lateinit var eventRepository: EventRepositoryPort

    @Autowired
    lateinit var reservationRepository: ReservationRepositoryPort

    @Autowired
    lateinit var paymentRepository: PaymentRepositoryPort

    @Autowired
    lateinit var claimService: PaymentClaimService

    @Autowired
    lateinit var policy: PaymentPolicyProperties

    @Autowired
    lateinit var clock: Clock

    @Test
    @Transactional
    fun `첫 클레임은 PENDING Payment를 새로 만든다`() {
        val event = eventRepository.공연_하나_저장()
        val reservation = reservationRepository.예약_하나_저장(event = event, amount = 100_000)

        val claimed = claimService.claimOrTakeOver(reservation.id, reservation.amount, LocalDateTime.now(clock))

        assertThat(claimed.status).isEqualTo(PaymentStatus.PENDING)
        assertThat(claimed.reservationId).isEqualTo(reservation.id)
        assertThat(claimed.amount).isEqualTo(100_000)
    }

    @Test
    @Transactional
    fun `fresh한 PENDING이 이미 있으면 충돌 예외를 던진다`() {
        val event = eventRepository.공연_하나_저장()
        val reservation = reservationRepository.예약_하나_저장(event = event)
        val now = LocalDateTime.now(clock)
        claimService.claimOrTakeOver(reservation.id, reservation.amount, now)

        assertThatThrownBy {
            claimService.claimOrTakeOver(reservation.id, reservation.amount, now)
        }.isInstanceOf(PaymentConflictException::class.java)
    }

    @Test
    @Transactional
    fun `stale한 PENDING은 새로 만들지 않고 그대로 이어받는다`() {
        val event = eventRepository.공연_하나_저장()
        val reservation = reservationRepository.예약_하나_저장(event = event)
        val claimedAt = LocalDateTime.now(clock)
        val first = claimService.claimOrTakeOver(reservation.id, reservation.amount, claimedAt)

        val muchLater = claimedAt.plus(policy.staleClaimTimeout).plusSeconds(1)
        val takenOver = claimService.claimOrTakeOver(reservation.id, reservation.amount, muchLater)

        assertThat(takenOver.id).isEqualTo(first.id)
        assertThat(takenOver.status).isEqualTo(PaymentStatus.PENDING)
    }
}
