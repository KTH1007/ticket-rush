package com.ticketrush.payment

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class TossPropertiesTest {
    @Test
    fun `문자열로 바꿔도 시크릿 키는 드러나지 않는다`() {
        val properties = TossProperties(baseUrl = "https://api.tosspayments.com", secretKey = "test_gsk_do_not_leak")

        assertThat(properties.toString()).doesNotContain("test_gsk_do_not_leak").contains("https://api.tosspayments.com")
    }
}
