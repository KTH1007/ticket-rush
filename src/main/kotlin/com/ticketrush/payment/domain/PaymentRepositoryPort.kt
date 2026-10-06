package com.ticketrush.payment.domain

import java.time.LocalDateTime

interface PaymentRepositoryPort {
    fun save(payment: Payment): Payment

    fun findByReservationId(reservationId: Long): Payment?

    // 회수 스케줄러 전용. 재시도에 필요한 paymentKey가 있는 PENDING 중 updatedAt이 오래된 행을 오래된 순서로 찾는다.
    // 환불 필요로 표시된 행은 사람이 처리 중이라 제외한다
    fun findStalePending(staleBefore: LocalDateTime): List<Payment>

    // 환불 필요로 표시됐고 아직 사람이 정리하지 않은(PENDING) 결제 수. 미처리 건수 지표용
    fun countRefundRequired(): Long

    // 환불 재시도 스케줄러 전용. 환불이 안 끝난 CANCELED 중 updatedAt이 오래되고 시도 횟수가 한도 미만인 행을 오래된 순서로 찾는다
    fun findStaleCanceled(
        staleBefore: LocalDateTime,
        maxAttempts: Int,
    ): List<Payment>
}
