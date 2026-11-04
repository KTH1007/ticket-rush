package com.ticketrush.e2e

import com.ticketrush.event.domain.Event
import com.ticketrush.event.domain.EventRepositoryPort
import com.ticketrush.reservation.domain.Grade
import com.ticketrush.reservation.domain.GradeRepositoryPort
import com.ticketrush.reservation.domain.Seat
import com.ticketrush.reservation.domain.SeatRepositoryPort
import java.time.LocalDateTime

// 공연, 등급, 좌석을 DB에 커밋해 둔다. 시각은 지금과 상관없이 정해진 값을 써서 테스트가 시계에 기대지 않게 한다
class E2eSeed(
    private val events: EventRepositoryPort,
    private val grades: GradeRepositoryPort,
    private val seats: SeatRepositoryPort,
) {
    data class Seeded(
        val eventId: Long,
        val seatIds: List<Long>,
        val price: Int,
    )

    // 이미 오픈했고 아직 시작하지 않은 공연
    fun openEvent(seatCount: Int = DEFAULT_SEATS): Seeded = create(seatCount, OPENED_AT, FAR_FUTURE)

    // 오픈 시각이 먼 미래인 공연(오픈 전)
    fun notOpenedEvent(): Seeded = create(DEFAULT_SEATS, FAR_FUTURE, FAR_FUTURE.plusDays(1))

    // 이미 시작한 공연
    fun startedEvent(): Seeded = create(DEFAULT_SEATS, OPENED_AT, OPENED_AT.plusDays(1))

    private fun create(
        seatCount: Int,
        opensAt: LocalDateTime,
        startsAt: LocalDateTime,
    ): Seeded {
        val event =
            events.save(Event(title = "e2e 공연", venue = "e2e 공연장", description = null, opensAt = opensAt, startsAt = startsAt))
        val grade = grades.save(Grade(eventId = event.id, name = "VIP", price = PRICE))
        val saved =
            (0 until seatCount).map { index ->
                seats.save(
                    Seat(
                        eventId = event.id,
                        gradeId = grade.id,
                        section = "A",
                        rowLabel = "1",
                        seatNo = (index + 1).toShort(),
                        ordinal = index,
                    ),
                )
            }
        return Seeded(event.id, saved.map { it.id }, PRICE)
    }

    companion object {
        private const val DEFAULT_SEATS = 8
        private const val PRICE = 100_000
        private val OPENED_AT = LocalDateTime.of(2026, 1, 1, 0, 0)
        private val FAR_FUTURE = LocalDateTime.of(2099, 1, 1, 19, 0)
    }
}
