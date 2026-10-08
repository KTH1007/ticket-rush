package com.ticketrush.payment.domain

import com.ticketrush.shared.exception.ConflictException

// 결과를 아직 확정할 수 없어 나중에 다시 시도하게 하는 충돌. 동시 요청에서 진 쪽, PG가 같은 요청을 처리 중이거나 일시 거부(429, 408)한 경우에 던진다.
// 재시도하면 멱등 체크(findByReservationId)가 이긴 쪽 결과를 그대로 돌려준다.
class PaymentConflictException(
    cause: Throwable? = null,
) : ConflictException(
        code = "PAYMENT_CONFLICT",
        message = "다른 요청이 이미 처리 중입니다. 잠시 후 다시 시도해주세요",
        cause = cause,
    )
