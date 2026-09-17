package com.ticketrush.payment.domain

// confirmPayment()의 반환값. Payment 엔티티만으론 예매번호(Reservation 소속)를
// 못 담아서, 컨트롤러가 응답을 만들 때 필요한 두 정보를 여기서 같이 묶어 돌려준다.
data class PaymentConfirmationResult(
    val payment: Payment,
    val reservationNo: String,
)
