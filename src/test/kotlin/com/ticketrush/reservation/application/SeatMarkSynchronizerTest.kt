package com.ticketrush.reservation.application

import com.ticketrush.reservation.domain.SeatHoldFilterPort
import com.ticketrush.reservation.domain.SeatHoldShortenedEvent
import com.ticketrush.reservation.domain.SeatsReturnedEvent
import com.ticketrush.reservation.domain.SeatsSoldEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThatCode
import org.springframework.data.redis.RedisConnectionFailureException
import java.util.UUID
import kotlin.test.Test

// 마크 동기화는 이미 커밋된 DB 변경 뒤의 부가 작업이라, 실패해도 결제나 취소 결과를 바꾸면 안 된다
class SeatMarkSynchronizerTest {
    private val seatHoldFilter = mockk<SeatHoldFilterPort>(relaxed = true)
    private val synchronizer = SeatMarkSynchronizer(seatHoldFilter)
    private val holdToken = UUID.randomUUID()

    @Test
    fun `판매 이벤트는 같은 토큰으로 마크를 늘린다`() {
        synchronizer.onSold(SeatsSoldEvent(EVENT_ID, SEAT_IDS, holdToken))

        verify(exactly = 1) { seatHoldFilter.extendForSold(EVENT_ID, SEAT_IDS, holdToken.toString()) }
    }

    @Test
    fun `홀드 단축 이벤트는 같은 토큰으로 마크를 줄인다`() {
        synchronizer.onHoldShortened(SeatHoldShortenedEvent(EVENT_ID, SEAT_IDS, holdToken))

        verify(exactly = 1) { seatHoldFilter.shortenAfterPaymentFailure(EVENT_ID, SEAT_IDS, holdToken.toString()) }
    }

    @Test
    fun `반환 이벤트는 같은 토큰으로 마크를 지운다`() {
        synchronizer.onReturned(SeatsReturnedEvent(EVENT_ID, SEAT_IDS, holdToken))

        verify(exactly = 1) { seatHoldFilter.release(EVENT_ID, SEAT_IDS, holdToken.toString()) }
    }

    @Test
    fun `Redis 오류가 나도 예외를 밖으로 던지지 않는다`() {
        every { seatHoldFilter.extendForSold(any(), any(), any()) } throws RedisConnectionFailureException("연결 실패")
        every { seatHoldFilter.shortenAfterPaymentFailure(any(), any(), any()) } throws RedisConnectionFailureException("연결 실패")
        every { seatHoldFilter.release(any(), any(), any()) } throws RedisConnectionFailureException("연결 실패")

        assertThatCode {
            synchronizer.onSold(SeatsSoldEvent(EVENT_ID, SEAT_IDS, holdToken))
            synchronizer.onHoldShortened(SeatHoldShortenedEvent(EVENT_ID, SEAT_IDS, holdToken))
            synchronizer.onReturned(SeatsReturnedEvent(EVENT_ID, SEAT_IDS, holdToken))
        }.doesNotThrowAnyException()
    }

    private companion object {
        const val EVENT_ID = 1L
        val SEAT_IDS = listOf(1L, 2L)
    }
}
