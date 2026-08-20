package com.ticketrush.event.domain

import com.ticketrush.shared.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

// 공연. 캐싱 대상인 정적 데이터 (V1__event_and_seat.sql 참고).
@Entity
@Table(name = "event")
class Event(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    title: String,
    venue: String,
    description: String? = null,
    opensAt: LocalDateTime,
    startsAt: LocalDateTime,
) : BaseEntity() {
    @Column(nullable = false, length = 200)
    var title: String = title
        protected set

    @Column(nullable = false, length = 200)
    var venue: String = venue
        protected set

    @Column
    var description: String? = description
        protected set

    // 예매 오픈 시각. 판정은 서버 시각(NTP 동기화) 기준으로만 한다.
    @Column(name = "opens_at", nullable = false)
    var opensAt: LocalDateTime = opensAt
        protected set

    // 공연 시작 시각. 예매 오픈과 다르다.
    @Column(name = "starts_at", nullable = false)
    var startsAt: LocalDateTime = startsAt
        protected set
}
