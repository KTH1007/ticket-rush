package com.ticketrush.payment.application

import com.ticketrush.payment.domain.ChargeIdempotencyKey
import com.ticketrush.payment.domain.Payment
import com.ticketrush.payment.domain.PaymentGatewayPort
import com.ticketrush.payment.domain.PaymentGatewayResult
import com.ticketrush.payment.domain.PaymentGatewayUnavailableException
import com.ticketrush.reservation.domain.Reservation
import com.ticketrush.shared.PhoneHash
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.util.UUID
import kotlin.test.Test

class PaymentChargerTest {
    private val paymentGateway = mockk<PaymentGatewayPort>()
    private val claimService = mockk<PaymentClaimService>(relaxed = true)
    private val charger = PaymentCharger(paymentGateway, claimService)
    private val reservation =
        Reservation(
            id = 1L,
            eventId = 10L,
            phoneHash = PhoneHash(ByteArray(32) { 1 }),
            quantity = 1,
            amount = 10_000,
            holdToken = UUID.randomUUID(),
            idempotencyKey = UUID.randomUUID(),
        )

    @Test
    fun `승인 거절이나 5xx로 오른 횟수를 섞은 멱등키로 요청한다`() {
        val payment = Payment(id = 1L, reservationId = 1L, amount = 10_000, chargeAttemptCount = 2)
        val expectedKey = ChargeIdempotencyKey.of(reservation.idempotencyKey, 2)
        every { paymentGateway.charge("pk", "order", 10_000, expectedKey) } returns PaymentGatewayResult.Approved("pk")

        val result = charger.charge(payment, reservation, "pk", "order", 10_000)

        assertThat(result).isEqualTo(PaymentGatewayResult.Approved("pk"))
    }

    @Test
    fun `Toss가 5xx를 응답했으면 횟수를 기록하고 예외를 그대로 던진다`() {
        val payment = Payment(id = 1L, reservationId = 1L, amount = 10_000)
        val unavailable = PaymentGatewayUnavailableException(serverResponded = true)
        every { paymentGateway.charge(any(), any(), any(), any()) } throws unavailable

        assertThatThrownBy { charger.charge(payment, reservation, "pk", "order", 10_000) }.isSameAs(unavailable)

        verify(exactly = 1) { claimService.recordServerError(payment) }
    }

    @Test
    fun `응답이 없던 실패는 횟수를 기록하지 않아 키가 유지된다`() {
        val payment = Payment(id = 1L, reservationId = 1L, amount = 10_000)
        every { paymentGateway.charge(any(), any(), any(), any()) } throws PaymentGatewayUnavailableException()

        assertThatThrownBy { charger.charge(payment, reservation, "pk", "order", 10_000) }
            .isInstanceOf(PaymentGatewayUnavailableException::class.java)

        verify(exactly = 0) { claimService.recordServerError(any()) }
    }
}
