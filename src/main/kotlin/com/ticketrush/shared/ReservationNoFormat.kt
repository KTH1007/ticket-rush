package com.ticketrush.shared

// 예매번호 생성기(ReservationNoGenerator)의 문자 집합과 길이와 같아야 한다. 값이 그대로 Redis 키에 들어가서 요청에서 먼저 거른다
object ReservationNoFormat {
    const val PATTERN = "^[2-9A-HJ-NP-Z]{12}$"
    const val MESSAGE = "올바른 예매번호 형식이 아닙니다"
}
