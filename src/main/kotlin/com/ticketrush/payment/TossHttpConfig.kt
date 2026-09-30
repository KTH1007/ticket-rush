package com.ticketrush.payment

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.web.client.RestClient

@Profile("toss")
@Configuration
class TossHttpConfig {
    @Bean
    fun restClient(): RestClient = RestClient.create()
}
