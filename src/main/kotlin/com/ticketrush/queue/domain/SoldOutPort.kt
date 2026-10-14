package com.ticketrush.queue.domain

// 대기열이 승격과 응답을 정할 때 공연이 매진됐는지 묻는 포트. 구현은 예약 모듈이 한다
interface SoldOutPort {
    fun isSoldOut(eventId: Long): Boolean
}
