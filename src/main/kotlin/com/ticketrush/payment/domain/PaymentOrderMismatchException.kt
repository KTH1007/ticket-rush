package com.ticketrush.payment.domain

import com.ticketrush.shared.exception.BusinessException

// 클라이언트가 보낸 orderId/amount가 서버가 아는 예약 정보와 다를 때. 위조나 클라이언트 버그를 의심한다.
class PaymentOrderMismatchException :
    BusinessException(
        code = "PAYMENT_ORDER_MISMATCH",
        message = "요청한 주문 정보가 예약과 일치하지 않습니다",
    )
