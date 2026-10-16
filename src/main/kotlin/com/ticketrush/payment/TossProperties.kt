package com.ticketrush.payment

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "ticket-rush.toss")
data class TossProperties(
    val baseUrl: String,
    val secretKey: String,
    val connectTimeout: Duration = Duration.ofSeconds(3),
    val readTimeout: Duration = Duration.ofSeconds(3),
) {
    init {
        require(connectTimeout.isPositive()) { "connectTimeout은 양수여야 합니다: $connectTimeout" }
        require(readTimeout.isPositive()) { "readTimeout은 양수여야 합니다: $readTimeout" }
    }

    // data class의 기본 toString은 시크릿 키까지 찍어서 로그나 예외 메시지로 새 나갈 수 있다
    override fun toString(): String = "TossProperties(baseUrl=$baseUrl, secretKey=****)"
}
