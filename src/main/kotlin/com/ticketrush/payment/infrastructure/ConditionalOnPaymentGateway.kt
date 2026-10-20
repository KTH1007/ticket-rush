package com.ticketrush.payment.infrastructure

import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.core.type.AnnotatedTypeMetadata

// @ConditionalOnProperty는 오타일 때 어댑터가 하나도 안 떠서 PG 빈이 없다는 오류만 난다. 설정 해석 단계에서 허용값을 알려 주며 멈추려고 직접 만든다
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Conditional(OnPaymentGatewayCondition::class)
annotation class ConditionalOnPaymentGateway(
    val value: PaymentGatewayType,
)

class OnPaymentGatewayCondition : Condition {
    override fun matches(
        context: ConditionContext,
        metadata: AnnotatedTypeMetadata,
    ): Boolean {
        val wanted = metadata.annotations.get(ConditionalOnPaymentGateway::class.java).getEnum("value", PaymentGatewayType::class.java)
        return PaymentGatewayType.from(context.environment) == wanted
    }
}
