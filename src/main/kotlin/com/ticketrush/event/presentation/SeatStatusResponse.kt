package com.ticketrush.event.presentation

import java.time.Instant

data class SeatStatusResponse(
    val eventId: Long,
    val seatCount: Int,
    // 클라이언트가 디코딩 규칙을 알 수 있게 함께 내려준다
    val bitsPerSeat: Int,
    // 이 상태를 만든 시각. 클라이언트가 지도의 나이를 볼 수 있다
    val builtAt: Instant,
    // base64. ordinal i의 상태는 바이트[i/4]의 상위 비트부터 (i%4)번째 2비트 (0 가능, 1 홀드, 2 판매)
    val bitmap: String,
)
