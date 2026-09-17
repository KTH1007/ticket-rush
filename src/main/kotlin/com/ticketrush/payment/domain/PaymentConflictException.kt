package com.ticketrush.payment.domain

import com.ticketrush.shared.exception.ConflictException

// 정확히 동시에 들어온 결제 확정 요청 중 진 쪽에게 던진다
// 재시도하면 멱등 체크(findByReservationId)가 이긴 쪽 결과를 그대로 돌려준다.
class PaymentConflictException(
    cause: Throwable? = null,
) : ConflictException(
        code = "PAYMENT_CONFLICT",
        message = "다른 요청이 이미 처리 중입니다. 잠시 후 다시 시도해주세요",
        cause = cause,
    )
