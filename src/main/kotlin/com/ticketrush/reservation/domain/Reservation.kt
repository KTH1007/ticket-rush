package com.ticketrush.reservation.domain

import com.ticketrush.shared.BaseEntity
import com.ticketrush.shared.PhoneHash
import com.ticketrush.shared.PhoneHashConverter
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "reservation")
class Reservation(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    eventId: Long,
    phoneHash: PhoneHash,
    quantity: Short,
    amount: Int,
    holdToken: UUID,
    idempotencyKey: UUID,
    reservationNo: String? = null,
    status: ReservationStatus = ReservationStatus.HOLDING,
    holdExpiresAt: LocalDateTime? = null,
    version: Long = 0,
) : BaseEntity() {
    @Column(name = "event_id", nullable = false)
    var eventId: Long = eventId
        protected set

    @Convert(converter = PhoneHashConverter::class)
    @Column(name = "phone_hash", nullable = false)
    var phoneHash: PhoneHash = phoneHash
        protected set

    @Column(nullable = false)
    var quantity: Short = quantity
        protected set

    @Column(nullable = false)
    var amount: Int = amount
        protected set

    @Column(name = "hold_token", nullable = false)
    var holdToken: UUID = holdToken
        protected set

    @Column(name = "idempotency_key", nullable = false)
    var idempotencyKey: UUID = idempotencyKey
        protected set

    @Column(name = "reservation_no", length = 12)
    var reservationNo: String? = reservationNo
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: ReservationStatus = status
        protected set

    @Column(name = "hold_expires_at")
    var holdExpiresAt: LocalDateTime? = holdExpiresAt
        protected set

    @Version
    @Column(nullable = false)
    var version: Long = version
        protected set

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Reservation) return false
        return id != 0L && id == other.id
    }

    override fun hashCode(): Int = javaClass.hashCode()
}
