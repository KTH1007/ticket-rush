package com.ticketrush.support

import com.ticketrush.event.domain.Event
import com.ticketrush.event.domain.EventRepositoryPort
import com.ticketrush.event.domain.Grade
import com.ticketrush.event.domain.GradeRepositoryPort
import com.ticketrush.event.domain.Seat
import com.ticketrush.event.domain.SeatRepositoryPort
import java.time.LocalDateTime

// EventRepositoryTest/GradeRepositoryTest/SeatRepositoryTest가 공통으로 쓰던
// 샘플 데이터 생성을 한 곳으로 모은 것. 각 테스트가 필요한 필드만 오버라이드한다.
fun EventRepositoryPort.공연_하나_저장(
    title: String = "아이유 콘서트",
    venue: String = "잠실종합운동장",
    description: String? = null,
    opensAt: LocalDateTime = LocalDateTime.of(2026, 9, 1, 10, 0),
    startsAt: LocalDateTime = LocalDateTime.of(2026, 9, 20, 19, 0),
): Event =
    save(
        Event(
            title = title,
            venue = venue,
            description = description,
            opensAt = opensAt,
            startsAt = startsAt,
        ),
    )

fun GradeRepositoryPort.등급_하나_저장(
    event: Event,
    name: String = "VIP",
    price: Int = 200_000,
): Grade = save(Grade(event = event, name = name, price = price))

fun SeatRepositoryPort.좌석_하나_저장(
    event: Event,
    grade: Grade,
    section: String = "A",
    rowLabel: String = "1",
    seatNo: Short = 1,
    ordinal: Int = 0,
): Seat =
    save(
        Seat(
            eventId = event.id,
            gradeId = grade.id,
            section = section,
            rowLabel = rowLabel,
            seatNo = seatNo,
            ordinal = ordinal,
        ),
    )
