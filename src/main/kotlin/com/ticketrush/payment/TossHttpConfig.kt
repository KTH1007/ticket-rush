package com.ticketrush.payment

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Duration

@Profile("toss")
@Configuration
class TossHttpConfig {
    // 동기 어댑터라 @TimeLimiter는 안 맞는다(비동기용). 타임아웃은 여기서 건다
    @Bean
    fun restClient(): RestClient {
        val requestFactory =
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(Duration.ofSeconds(3))
                setReadTimeout(Duration.ofSeconds(3))
            }
        return RestClient.builder().requestFactory(requestFactory).build()
    }
}
