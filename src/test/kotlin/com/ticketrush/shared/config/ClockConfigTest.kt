package com.ticketrush.shared.config

import com.ticketrush.shared.AppTimeZone
import org.assertj.core.api.Assertions.assertThat
import java.util.TimeZone
import kotlin.test.Test

class ClockConfigTest {
    @Test
    fun `JVM 기본 시간대가 UTC여도 Clock은 AppTimeZone이다`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

            assertThat(ClockConfig().clock().zone).isEqualTo(AppTimeZone.ID)
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
