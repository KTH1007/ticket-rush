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
    tossPaymentKey: String? = null,
    tossOrderId: String? = null,
    refundRequiredAt: LocalDateTime? = null,
    chargeAttemptCount: Int = 0,
    refundAttemptCount: Int = 0,
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

    @Column(name = "toss_payment_key", length = 200)
    var tossPaymentKey: String? = tossPaymentKey
        protected set

    @Column(name = "toss_order_id", length = 64)
    var tossOrderId: String? = tossOrderId
        protected set

    @Column(name = "refund_required_at")
    var refundRequiredAt: LocalDateTime? = refundRequiredAt
        protected set

    @Column(name = "charge_attempt_count", nullable = false)
    var chargeAttemptCount: Int = chargeAttemptCount
        protected set

    @Column(name = "refund_attempt_count", nullable = false)
    var refundAttemptCount: Int = refundAttemptCount
        protected set

    @Version
    @Column(nullable = false)
    var version: Long = version
        protected set

    // 회수 스케줄러가 같은 값으로 PG를 다시 부를 수 있게 클레임 시점의 paymentKey/orderId를 남긴다
    fun recordAttempt(
        paymentKey: String,
        orderId: String,
        claimedAt: LocalDateTime,
    ) {
        this.tossPaymentKey = paymentKey
        this.tossOrderId = orderId
        // 같은 값으로 이어받아도 변경으로 잡혀야 UPDATE가 나가 updatedAt과 version이 움직이고, 다른 요청이 이어받지 못한다
        this.updatedAt = claimedAt
    }

    // 결제 성공 처리. 거절됐던 결제도 reopen()으로 PENDING이 된 뒤에 같은 행이 다시 성공한다
    // 예약당 행 하나를 강제하므로, 시도마다 새 행을 만드는 게 아니라 같은 행을 계속 갱신
    fun markSuccess(
        pgTransactionId: String,
        paidAt: LocalDateTime,
    ) {
        check(status == PaymentStatus.PENDING) { "PENDING 상태에서만 성공 처리할 수 있습니다: $status" }
        status = PaymentStatus.SUCCESS
        this.pgTransactionId = pgTransactionId
        this.paidAt = paidAt
    }

    fun markFailed() {
        check(status == PaymentStatus.PENDING) { "PENDING 상태에서만 실패 처리할 수 있습니다: $status" }
        status = PaymentStatus.FAILED
    }

    // 확정 거절로 끝난 시도를 센다. 이 횟수가 다음 승인 요청의 멱등키에 섞여 저장된 거절이 재생되지 않게 한다
    fun recordChargeFailure() {
        check(status == PaymentStatus.FAILED) { "FAILED 상태에서만 승인 거절을 기록할 수 있습니다: $status" }
        chargeAttemptCount++
    }

    // Toss는 승인했는데 예약이 만료돼 환불이 필요한 결제를 표시한다. 상태는 PENDING 그대로 두고 사람이 처리한다
    fun markRefundRequired(markedAt: LocalDateTime) {
        check(status == PaymentStatus.PENDING) { "PENDING 상태에서만 환불 필요를 표시할 수 있습니다: $status" }
        refundRequiredAt = markedAt
    }

    // 거절된 결제를 새 시도에서 다시 연다. FAILED로 두면 응답이 없을 때 회수 스케줄러(PENDING만 조회)가 대사하지 못한다
    fun reopen() {
        check(status == PaymentStatus.FAILED) { "FAILED 상태에서만 다시 열 수 있습니다: $status" }
        status = PaymentStatus.PENDING
    }

    fun markCanceled() {
        check(status == PaymentStatus.SUCCESS) { "SUCCESS 상태에서만 취소할 수 있습니다: $status" }
        status = PaymentStatus.CANCELED
    }

    fun markRefunded() {
        check(status == PaymentStatus.CANCELED) { "CANCELED 상태에서만 환불 완료 처리할 수 있습니다: $status" }
        status = PaymentStatus.REFUNDED
    }

    // 취소 시점과 재시도의 환불이 실패할 때마다 올린다. 저장하면 updatedAt도 갱신돼 다음 재시도까지 간격이 벌어지고 환불 키도 바뀐다
    fun recordRefundAttempt() {
        check(status == PaymentStatus.CANCELED) { "CANCELED 상태에서만 환불 시도를 기록할 수 있습니다: $status" }
        refundAttemptCount++
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Payment) return false
        return id != 0L && id == other.id
    }

    override fun hashCode(): Int = Payment::class.java.hashCode()
}
