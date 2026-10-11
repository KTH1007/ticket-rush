package com.ticketrush.reservation.domain

// 좌석 지도 전용 읽기 모델. 저장소 포트와 역할이 달라 분리했고, 좌석 엔티티를 만들지 않고 필요한 컬럼만 읽는다
interface SeatMapQueryPort {
    // 배치용. ordinal 순서
    fun findLayoutRowsByEventId(eventId: Long): List<SeatLayoutRow>

    // 상태용
    fun findStatusRowsByEventId(eventId: Long): List<SeatStatusRow>
}
