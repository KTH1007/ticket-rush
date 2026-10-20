package com.ticketrush.payment.infrastructure

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient

@ConditionalOnPaymentGateway(PaymentGatewayType.TOSS)
@Configuration
class TossHttpConfig {
    // 동기 어댑터라 @TimeLimiter는 안 맞는다(비동기용). 타임아웃은 여기서 건다
    @Bean
    fun restClient(toss: TossProperties): RestClient {
        val requestFactory =
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(toss.connectTimeout)
                setReadTimeout(toss.readTimeout)
            }
        return RestClient.builder().requestFactory(requestFactory).build()
    }
}
