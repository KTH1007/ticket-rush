package com.ticketrush.payment.domain

import com.ticketrush.shared.exception.ServiceUnavailableException
import java.time.Duration

// PG가 일시적으로 요청을 받지 못한다(서킷 OPEN, 승인과 조회의 5xx와 타임아웃, 조회의 429와 408).
// 결과를 확정하지 않으므로 결제는 PENDING으로 두고 다시 시도하게 한다
class PaymentGatewayUnavailableException(
    retryAfter: Duration? = null,
    cause: Throwable? = null,
) : ServiceUnavailableException(
        code = "PAYMENT_GATEWAY_UNAVAILABLE",
        message = "결제 서비스가 일시적으로 응답하지 않습니다. 잠시 후 다시 시도해주세요",
        retryAfter = retryAfter,
        cause = cause,
    )
