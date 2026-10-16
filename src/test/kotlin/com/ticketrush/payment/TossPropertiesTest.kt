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
}
