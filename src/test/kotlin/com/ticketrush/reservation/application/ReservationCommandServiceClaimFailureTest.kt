package com.ticketrush.reservation.application

import com.ticketrush.reservation.domain.SeatAlreadyHeldException
import com.ticketrush.reservation.domain.SeatHoldFilterPort
import com.ticketrush.reservation.domain.SeatSelection
import com.ticketrush.shared.PhoneHash
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.springframework.data.redis.RedisConnectionFailureException
import java.time.Clock
import kotlin.test.Test

class ReservationCommandServiceClaimFailureTest {
    private val seatHoldFilter = mockk<SeatHoldFilterPort>(relaxed = true)
    private val service =
        ReservationCommandService(
            seatRepository = mockk(),
            reservationRepository = mockk(),
            gradeRepository = mockk(),
            seatHoldFilter = seatHoldFilter,
            clock = Clock.systemUTC(),
            seatPolicy = mockk(),
            transactionTemplate = mockk(),
        )

    @Test
    fun `선점 중 예외가 나면 같은 토큰으로 해제를 시도하고 원래 예외를 던진다`() {
        // given
        every { seatHoldFilter.tryClaim(any(), any(), any()) } throws RedisConnectionFailureException("타임아웃")

        // when & then
        assertThatThrownBy { 홀드() }.isInstanceOf(RedisConnectionFailureException::class.java)
        verify(exactly = 1) { seatHoldFilter.release(EVENT_ID, SEAT_IDS, any()) }
    }

    @Test
    fun `이미 잡힌 좌석이면 해제를 부르지 않고 SeatAlreadyHeld를 던진다`() {
        // given
        every { seatHoldFilter.tryClaim(any(), any(), any()) } returns false

        // when & then
        assertThatThrownBy { 홀드() }.isInstanceOf(SeatAlreadyHeldException::class.java)
        verify(exactly = 0) { seatHoldFilter.release(any(), any(), any()) }
    }

    private fun 홀드() = service.holdSeats(EVENT_ID, SeatSelection(SEAT_IDS), PhoneHash(ByteArray(32)), mockk(relaxed = true))

    private companion object {
        const val EVENT_ID = 1L
        val SEAT_IDS = listOf(1L, 2L)
    }
}
