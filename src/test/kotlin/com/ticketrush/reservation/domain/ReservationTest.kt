package com.ticketrush.reservation.domain

import com.ticketrush.shared.PhoneHash
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.Test

class ReservationTest {
    @Test
    fun `id가 같으면 동일한 엔티티로 판단한다`() {
        // given
        val reservation1 = 예약(id = 1L)
        val reservation2 = 예약(id = 1L)

        // when & then
        assertThat(reservation1).isEqualTo(reservation2)
    }

    @Test
    fun `id가 다르면 동일하지 않다`() {
        // given
        val reservation1 = 예약(id = 1L)
        val reservation2 = 예약(id = 2L)

        // when & then
        assertThat(reservation1).isNotEqualTo(reservation2)
    }

    @Test
    fun `아직 저장되지 않은 엔티티끼리는 동일하지 않다`() {
        // given
        val reservation1 = 예약(id = 0L)
        val reservation2 = 예약(id = 0L)

        // when & then
        assertThat(reservation1).isNotEqualTo(reservation2)
    }

    @Test
    fun `동일한 엔티티는 hashCode도 같다`() {
        // given
        val reservation1 = 예약(id = 1L)
        val reservation2 = 예약(id = 1L)

        // when & then
        assertThat(reservation1.hashCode()).isEqualTo(reservation2.hashCode())
    }

    @Test
    fun `HOLDING 상태에서 결제를 확정하면 PAID로 바뀐다`() {
        // given
        val reservation = 예약(id = 1L)

        // when
        reservation.confirmPayment()

        // then
        assertThat(reservation.status).isEqualTo(ReservationStatus.PAID)
    }

    @Test
    fun `HOLDING이 아닌 상태에서 결제를 확정하려 하면 예외가 발생한다`() {
        // given
        val reservation = 예약(id = 1L)
        reservation.confirmPayment()

        // when & then
        assertThatThrownBy { reservation.confirmPayment() }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `PAID 상태에서 예매번호를 배정할 수 있고, 다시 배정하면 값이 바뀐다`() {
        // given
        val reservation = 예약(id = 1L)
        reservation.confirmPayment()

        // when
        reservation.assignReservationNo("RES00000001")
        reservation.assignReservationNo("RES00000002")

        // then
        assertThat(reservation.reservationNo).isEqualTo("RES00000002")
    }

    @Test
    fun `PAID가 아닌 상태에서 예매번호를 배정하려 하면 예외가 발생한다`() {
        // given
        val reservation = 예약(id = 1L)

        // when & then
        assertThatThrownBy { reservation.assignReservationNo("RES00000001") }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `HOLDING 상태에서 홀드 만료 시각을 단축할 수 있다`() {
        // given
        val reservation = 예약(id = 1L)
        val shortened = LocalDateTime.of(2026, 1, 1, 0, 1)

        // when
        reservation.shortenHoldOnPaymentFailure(shortened)

        // then
        assertThat(reservation.holdExpiresAt).isEqualTo(shortened)
    }

    @Test
    fun `새 만료 시각이 기존보다 늦으면 연장하지 않는다`() {
        // given
        val original = LocalDateTime.of(2026, 1, 1, 0, 0)
        val reservation = 예약(id = 1L, holdExpiresAt = original)

        // when
        reservation.shortenHoldOnPaymentFailure(original.plusMinutes(1))

        // then
        assertThat(reservation.holdExpiresAt).isEqualTo(original)
    }

    @Test
    fun `HOLDING이 아닌 상태에서 홀드 만료 시각을 단축하려 하면 예외가 발생한다`() {
        // given
        val reservation = 예약(id = 1L)
        reservation.confirmPayment()

        // when & then
        assertThatThrownBy { reservation.shortenHoldOnPaymentFailure(LocalDateTime.of(2026, 1, 1, 0, 1)) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `HOLDING 상태이고 만료 시각 전이면 홀드가 유효하다`() {
        // given
        val reservation = 예약(id = 1L, holdExpiresAt = LocalDateTime.of(2026, 1, 1, 0, 10))

        // when & then
        assertThat(reservation.isHoldActiveAt(LocalDateTime.of(2026, 1, 1, 0, 0))).isTrue()
    }

    @Test
    fun `HOLDING 상태여도 만료 시각이 지났으면 홀드가 유효하지 않다`() {
        // given
        val reservation = 예약(id = 1L, holdExpiresAt = LocalDateTime.of(2026, 1, 1, 0, 0))

        // when & then
        assertThat(reservation.isHoldActiveAt(LocalDateTime.of(2026, 1, 1, 0, 10))).isFalse()
    }

    @Test
    fun `HOLDING이 아니면 만료 시각과 무관하게 홀드가 유효하지 않다`() {
        // given
        val reservation = 예약(id = 1L, holdExpiresAt = LocalDateTime.of(2030, 1, 1, 0, 0))
        reservation.confirmPayment()

        // when & then
        assertThat(reservation.isHoldActiveAt(LocalDateTime.of(2026, 1, 1, 0, 0))).isFalse()
    }

    private fun 예약(
        id: Long,
        holdExpiresAt: LocalDateTime = LocalDateTime.of(2030, 1, 1, 0, 0),
    ): Reservation =
        Reservation(
            id = id,
            eventId = 1L,
            phoneHash = PhoneHash(ByteArray(32) { 1 }),
            quantity = 1,
            amount = 100_000,
            holdToken = UUID.randomUUID(),
            idempotencyKey = UUID.randomUUID(),
            holdExpiresAt = holdExpiresAt,
        )
}
