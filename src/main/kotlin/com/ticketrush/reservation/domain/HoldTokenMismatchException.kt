package com.ticketrush.reservation.domain

import com.ticketrush.shared.exception.UnauthorizedException

// holdToken이 그 예약의 실제 소유권 증명과 일치하지 않을 때. InvalidQueueTokenException과
// 같은 이유(신원 확인 실패)로 401에 매핑한다.
class HoldTokenMismatchException :
    UnauthorizedException(
        code = "HOLD_TOKEN_MISMATCH",
        message = "홀드 토큰이 일치하지 않습니다",
    )
