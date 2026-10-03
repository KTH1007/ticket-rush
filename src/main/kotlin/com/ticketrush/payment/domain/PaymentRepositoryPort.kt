package com.ticketrush.payment.domain

import java.time.LocalDateTime

interface PaymentRepositoryPort {
    fun save(payment: Payment): Payment

    fun findByReservationId(reservationId: Long): Payment?

    // 회수 스케줄러 전용. 재시도에 필요한 paymentKey가 있는 PENDING 중 updatedAt이 오래된 행을 찾는다
    fun findStalePending(staleBefore: LocalDateTime): List<Payment>
}
