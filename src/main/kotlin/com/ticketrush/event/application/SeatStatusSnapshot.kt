package com.ticketrush.event.application

import java.time.Instant

// 어느 순간 한 공연의 좌석 상태를 찍어 둔 사본. 요청마다 base64로 바꾸지 않으려고 만들 때 한 번만 변환한다
class SeatStatusSnapshot(
    val eventId: Long,
    val builtAt: Instant,
    val seatCount: Int,
    val bitmapBase64: String,
)
