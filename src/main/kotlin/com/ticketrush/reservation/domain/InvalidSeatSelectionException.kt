package com.ticketrush.reservation.domain

import com.ticketrush.shared.exception.BusinessException

// SeatSelection의 불변식(1~2개, 중복 없음) 위반 시 던진다. 클라이언트 입력이 원인이라
// 404/409 같은 구체적 상태가 필요 없어 BusinessException을 직접 상속해 400으로 처리한다.
class InvalidSeatSelectionException(
    message: String,
) : BusinessException(code = "INVALID_SEAT_SELECTION", message = message)
