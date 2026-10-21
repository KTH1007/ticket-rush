package com.ticketrush.payment.application

// 예외 번역이 이 이름으로 위반을 구분하므로 마이그레이션에서 이름을 바꾸면 번역이 조용히 빗나간다
internal object PaymentConstraints {
    const val RESERVATION = "uk_payment_reservation"
    const val TOSS_PAYMENT_KEY = "uk_payment_toss_payment_key"
}
