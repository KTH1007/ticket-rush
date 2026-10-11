package com.ticketrush.shared

import java.time.ZoneId

// 시간대가 두 곳 이상에 정의되면 어긋나는 만큼 LocalDateTime이 저장과 비교에서 틀어져서, 정의는 여기 한 곳에만 둔다
object AppTimeZone {
    val ID: ZoneId = ZoneId.of("Asia/Seoul")
}
