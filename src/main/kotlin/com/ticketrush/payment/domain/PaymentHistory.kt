package com.ticketrush.payment.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

// BaseEntity를 상속하지 않는다. payment_history는 추가만 되는 로그라 updated_at 컬럼이
// 없는데, BaseEntity는 created_at/updated_at을 세트로 강제해서 여기엔 안 맞는다.
@Entity
@Table(name = "payment_history")
class PaymentHistory(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    paymentId: Long,
    fromStatus: PaymentStatus?,
    toStatus: PaymentStatus,
    reason: String? = null,
    createdAt: LocalDateTime,
) {
    @Column(name = "payment_id", nullable = false)
    var paymentId: Long = paymentId
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20)
    var fromStatus: PaymentStatus? = fromStatus
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20)
    var toStatus: PaymentStatus = toStatus
        protected set

    @Column(length = 200)
    var reason: String? = reason
        protected set

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = createdAt

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PaymentHistory) return false
        return id != 0L && id == other.id
    }

    override fun hashCode(): Int = PaymentHistory::class.java.hashCode()
}
