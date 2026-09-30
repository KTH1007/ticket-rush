package com.ticketrush.payment

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "ticket-rush.toss")
data class TossProperties(
    val baseUrl: String,
    val secretKey: String,
)
