package com.ticketrush.payment

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "ticket-rush.toss")
data class TossProperties(
    val baseUrl: String,
    val secretKey: String,
) {
    // data class의 기본 toString은 시크릿 키까지 찍어서 로그나 예외 메시지로 새 나갈 수 있다
    override fun toString(): String = "TossProperties(baseUrl=$baseUrl, secretKey=****)"
}
