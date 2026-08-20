package com.ticketrush.event.domain

import com.ticketrush.shared.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.LocalDateTime

@Entity
@Table(name = "seat")
class Seat(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    eventId: Long,
    gradeId: Long,
    section: String,
    rowLabel: String,
    seatNo: Short,
    ordinal: Int,
    status: SeatStatus = SeatStatus.AVAILABLE,
    reservationId: Long? = null,
    phoneHash: ByteArray? = null,
    slotNo: Short? = null,
    holdExpiresAt: LocalDateTime? = null,
    version: Long = 0,
) : BaseEntity() {
    @Column(name = "event_id", nullable = false)
    var eventId: Long = eventId
        protected set

    @Column(name = "grade_id", nullable = false)
    var gradeId: Long = gradeId
        protected set

    @Column(nullable = false, length = 20)
    var section: String = section
        protected set

    @Column(name = "row_label", nullable = false, length = 10)
    var rowLabel: String = rowLabel
        protected set

    @Column(name = "seat_no", nullable = false)
    var seatNo: Short = seatNo
        protected set

    @Column(nullable = false)
    var ordinal: Int = ordinal
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: SeatStatus = status
        protected set

    @Column(name = "reservation_id")
    var reservationId: Long? = reservationId
        protected set

    @Column(name = "phone_hash")
    var phoneHash: ByteArray? = phoneHash
        protected set

    @Column(name = "slot_no")
    var slotNo: Short? = slotNo
        protected set

    @Column(name = "hold_expires_at")
    var holdExpiresAt: LocalDateTime? = holdExpiresAt
        protected set

    @Version
    @Column(nullable = false)
    var version: Long = version
        protected set
}
