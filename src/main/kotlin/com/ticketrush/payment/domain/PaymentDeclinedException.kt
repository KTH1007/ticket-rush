package com.ticketrush.payment.domain

import com.ticketrush.shared.exception.ConflictException

class PaymentDeclinedException(
    reason: String,
) : ConflictException(code = "PAYMENT_DECLINED", message = "결제가 거절되었습니다: $reason")
