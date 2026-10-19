package com.ticketrush.payment

import org.springframework.core.env.Environment

const val PAYMENT_GATEWAY_PROPERTY = "ticket-rush.payment.gateway"

enum class PaymentGatewayType {
    FAKE,
    TOSS,
    ;

    companion object {
        // 값이 없거나 모르는 값이면 어느 어댑터도 고르지 않고 기동을 멈춘다. 프로필로 암시하던 때는 값이 빠져도 조용히 가짜가 됐다
        fun from(environment: Environment): PaymentGatewayType {
            val raw = environment.getProperty(PAYMENT_GATEWAY_PROPERTY)?.trim().orEmpty()
            check(raw.isNotEmpty()) {
                "$PAYMENT_GATEWAY_PROPERTY 가 없습니다. fake 또는 toss로 명시해야 합니다(환경 변수 TICKET_RUSH_PAYMENT_GATEWAY)"
            }
            return entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: error("$PAYMENT_GATEWAY_PROPERTY 의 값을 알 수 없습니다: '$raw'. fake 또는 toss만 쓸 수 있습니다")
        }
    }
}
