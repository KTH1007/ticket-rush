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
        require(connectTimeout.isValidTimeout()) { "connectTimeout은 1ms 이상 ${Int.MAX_VALUE}ms 이하여야 합니다: $connectTimeout" }
        require(readTimeout.isValidTimeout()) { "readTimeout은 1ms 이상 ${Int.MAX_VALUE}ms 이하여야 합니다: $readTimeout" }
    }

    // data class의 기본 toString은 시크릿 키까지 찍어서 로그나 예외 메시지로 새 나갈 수 있다
    override fun toString(): String = "TossProperties(baseUrl=$baseUrl, secretKey=****)"

    // HTTP 클라이언트가 밀리초 int로 바꿔 쓴다. 1ms 미만은 0이 돼 타임아웃이 꺼지고, 상한을 넘으면 값이 넘친다
    private fun Duration.isValidTimeout() = this >= MIN_TIMEOUT && this <= MAX_TIMEOUT

    private companion object {
        val MIN_TIMEOUT: Duration = Duration.ofMillis(1)
        val MAX_TIMEOUT: Duration = Duration.ofMillis(Int.MAX_VALUE.toLong())
    }
}
