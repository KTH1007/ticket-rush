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

    // 결제 확정 상태 전이. 서비스 계층이 HOLDING인지 이미 확인하고 호출해야 한다.
    fun confirmPayment() {
        check(status == ReservationStatus.HOLDING) { "HOLDING 상태에서만 결제를 확정할 수 있습니다: $status" }
        status = ReservationStatus.PAID
    }

    // 예매번호 배정
    // confirmPayment()는 한 번만 호출되고, 이건 저장이 성공할 때까지 여러 번 호출될 수 있다.
    fun assignReservationNo(reservationNo: String) {
        check(status == ReservationStatus.PAID) { "PAID 상태에서만 예매번호를 배정할 수 있습니다: $status" }
        this.reservationNo = reservationNo
    }

    // 결제 실패 시 홀드 재시도 창을 좁힘
    fun shortenHoldOnPaymentFailure(newExpiresAt: LocalDateTime) {
        check(status == ReservationStatus.HOLDING) { "HOLDING 상태에서만 홀드 시간을 줄일 수 있습니다: $status" }
        val current = requireNotNull(holdExpiresAt) { "HOLDING 상태인데 holdExpiresAt이 없습니다: $id" }
        if (newExpiresAt < current) {
            holdExpiresAt = newExpiresAt
        }
    }

    fun isHoldActiveAt(now: LocalDateTime): Boolean = status == ReservationStatus.HOLDING && holdExpiresAt?.isAfter(now) == true

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Reservation) return false
        return id != 0L && id == other.id
    }

    override fun hashCode(): Int = Reservation::class.java.hashCode()
}
