package com.ticketrush.payment

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.time.Duration
import kotlin.test.Test

class TossPropertiesTest {
    @Test
    fun `문자열로 바꿔도 시크릿 키는 드러나지 않는다`() {
        val properties = TossProperties(baseUrl = "https://api.tosspayments.com", secretKey = "test_gsk_do_not_leak")

        assertThat(properties.toString()).doesNotContain("test_gsk_do_not_leak").contains("https://api.tosspayments.com")
    }

    @Test
    fun `타임아웃을 생략하면 연결과 읽기 모두 기본 3초다`() {
        val properties = TossProperties(baseUrl = "https://api.tosspayments.com", secretKey = "k")

        assertThat(properties.connectTimeout).isEqualTo(Duration.ofSeconds(3))
        assertThat(properties.readTimeout).isEqualTo(Duration.ofSeconds(3))
    }

    @Test
    fun `타임아웃이 0 이하면 예외가 난다`() {
        assertThatThrownBy { TossProperties(baseUrl = "u", secretKey = "k", connectTimeout = Duration.ZERO) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { TossProperties(baseUrl = "u", secretKey = "k", readTimeout = Duration.ofSeconds(-1)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    // 1ms 미만은 HTTP 클라이언트가 0ms로 바꿔 타임아웃이 꺼지고, int 범위를 넘으면 값이 넘친다
    @Test
    fun `타임아웃은 1ms 이상 int 최대 밀리초 이하만 허용한다`() {
        val max = Duration.ofMillis(Int.MAX_VALUE.toLong())

        assertThat(TossProperties(baseUrl = "u", secretKey = "k", connectTimeout = Duration.ofMillis(1), readTimeout = max).readTimeout)
            .isEqualTo(max)
        assertThatThrownBy { TossProperties(baseUrl = "u", secretKey = "k", connectTimeout = Duration.ofNanos(999_999)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("connectTimeout")
        assertThatThrownBy { TossProperties(baseUrl = "u", secretKey = "k", readTimeout = max.plusMillis(1)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("readTimeout")
    }
}
