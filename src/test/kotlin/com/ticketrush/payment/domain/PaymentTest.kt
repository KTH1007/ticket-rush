package com.ticketrush.payment.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.time.LocalDateTime
import kotlin.test.Test

class PaymentTest {
    @Test
    fun `id가 같으면 동일한 엔티티로 판단한다`() {
        // given
        val payment1 = 결제(id = 1L)
        val payment2 = 결제(id = 1L)

        // when & then
        assertThat(payment1).isEqualTo(payment2)
    }

    @Test
    fun `id가 다르면 동일하지 않다`() {
        // given
        val payment1 = 결제(id = 1L)
        val payment2 = 결제(id = 2L)

        // when & then
        assertThat(payment1).isNotEqualTo(payment2)
    }

    @Test
    fun `아직 저장되지 않은 엔티티끼리는 동일하지 않다`() {
        // given
        val payment1 = 결제(id = 0L)
        val payment2 = 결제(id = 0L)

        // when & then
        assertThat(payment1).isNotEqualTo(payment2)
    }

    @Test
    fun `동일한 엔티티는 hashCode도 같다`() {
        // given
        val payment1 = 결제(id = 1L)
        val payment2 = 결제(id = 1L)

        // when & then
        assertThat(payment1.hashCode()).isEqualTo(payment2.hashCode())
    }

    @Test
    fun `PENDING 상태에서 성공 처리하면 SUCCESS로 바뀌고 거래id와 결제시각이 설정된다`() {
        // given
        val payment = 결제(id = 1L)
        val paidAt = LocalDateTime.of(2026, 1, 1, 0, 0)

        // when
        payment.markSuccess(pgTransactionId = "PG-TXN-1", paidAt = paidAt)

        // then
        assertThat(payment.status).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(payment.pgTransactionId).isEqualTo("PG-TXN-1")
        assertThat(payment.paidAt).isEqualTo(paidAt)
    }

    @Test
    fun `이미 SUCCESS인 결제를 다시 성공 처리하려 하면 예외가 발생한다`() {
        // given
        val payment = 결제(id = 1L)
        payment.markSuccess(pgTransactionId = "PG-TXN-1", paidAt = LocalDateTime.of(2026, 1, 1, 0, 0))

        // when & then
        assertThatThrownBy { payment.markSuccess(pgTransactionId = "PG-TXN-2", paidAt = LocalDateTime.of(2026, 1, 1, 0, 1)) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `PENDING 상태에서 실패 처리하면 FAILED로 바뀐다`() {
        // given
        val payment = 결제(id = 1L)

        // when
        payment.markFailed()

        // then
        assertThat(payment.status).isEqualTo(PaymentStatus.FAILED)
    }

    @Test
    fun `FAILED였던 결제도 재시도로 성공 처리할 수 있다`() {
        // given
        val payment = 결제(id = 1L)
        payment.markFailed()

        // when
        payment.markSuccess(pgTransactionId = "PG-TXN-1", paidAt = LocalDateTime.of(2026, 1, 1, 0, 0))

        // then
        assertThat(payment.status).isEqualTo(PaymentStatus.SUCCESS)
    }

    private fun 결제(id: Long): Payment =
        Payment(
            id = id,
            reservationId = 1L,
            amount = 100_000,
        )
}
