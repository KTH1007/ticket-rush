package com.ticketrush.payment.domain

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
@Table(name = "payment")
class Payment(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    reservationId: Long,
    amount: Int,
    pgTransactionId: String? = null,
    status: PaymentStatus = PaymentStatus.PENDING,
    paidAt: LocalDateTime? = null,
    version: Long = 0,
) : BaseEntity() {
    @Column(name = "reservation_id", nullable = false)
    var reservationId: Long = reservationId
        protected set

    @Column(nullable = false)
    var amount: Int = amount
        protected set

    @Column(name = "pg_transaction_id", length = 100)
    var pgTransactionId: String? = pgTransactionId
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: PaymentStatus = status
        protected set

    @Column(name = "paid_at")
    var paidAt: LocalDateTime? = paidAt
        protected set

    @Version
    @Column(nullable = false)
    var version: Long = version
        protected set

    // 결제 성공 처리. FAILED였던 결제도 재시도로 다시 성공시킬 수 있음
    // 예약당 행 하나를 강제하므로, 시도마다 새 행을 만드는 게 아니라 같은 행을 계속 갱신
    fun markSuccess(
        pgTransactionId: String,
        paidAt: LocalDateTime,
    ) {
        check(status != PaymentStatus.SUCCESS) { "이미 SUCCESS 상태입니다" }
        status = PaymentStatus.SUCCESS
        this.pgTransactionId = pgTransactionId
        this.paidAt = paidAt
    }

    fun markFailed() {
        status = PaymentStatus.FAILED
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Payment) return false
        return id != 0L && id == other.id
    }

    override fun hashCode(): Int = Payment::class.java.hashCode()
}
