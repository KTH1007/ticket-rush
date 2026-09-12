package com.ticketrush.reservation.domain

import com.ticketrush.shared.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "grade")
class Grade(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    eventId: Long,
    name: String,
    price: Int,
) : BaseEntity() {
    // event 객체 참조 대신 ID 간접참조로 둔다. Grade가 reservation 모듈로
    // 옮겨오면서, event 모듈의 Event 엔티티를 직접 참조하면 event <-> reservation
    // 순환 의존이 생긴다(event가 이미 Seat 조회 때문에 reservation을 참조 중).
    @Column(name = "event_id", nullable = false)
    var eventId: Long = eventId
        protected set

    @Column(nullable = false, length = 50)
    var name: String = name
        protected set

    @Column(nullable = false)
    var price: Int = price
        protected set

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Grade) return false
        return id != 0L && id == other.id
    }

    override fun hashCode(): Int = javaClass.hashCode()
}
