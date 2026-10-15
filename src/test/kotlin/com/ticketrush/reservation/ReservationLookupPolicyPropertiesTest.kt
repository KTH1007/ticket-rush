package com.ticketrush.reservation

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.time.Duration
import kotlin.test.Test

class ReservationLookupPolicyPropertiesTest {
    private fun properties(
        ipMaxAttempts: Int = 50,
        ipBlockWindow: Duration = Duration.ofMinutes(10),
    ) = ReservationLookupPolicyProperties(
        maxAttempts = 5,
        blockWindow = Duration.ofMinutes(10),
        ipMaxAttempts = ipMaxAttempts,
        ipBlockWindow = ipBlockWindow,
    )

    @Test
    fun `IP 한도는 기본값이 10분에 50회다`() {
        val defaulted = ReservationLookupPolicyProperties(maxAttempts = 5, blockWindow = Duration.ofMinutes(10))

        assertThat(defaulted.ipMaxAttempts).isEqualTo(50)
        assertThat(defaulted.ipBlockWindow).isEqualTo(Duration.ofMinutes(10))
    }

    @Test
    fun `IP 한도가 0 이하면 거부한다`() {
        assertThatThrownBy { properties(ipMaxAttempts = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("ipMaxAttempts")
    }

    @Test
    fun `IP 윈도우가 1초 미만이면 거부한다`() {
        assertThatThrownBy { properties(ipBlockWindow = Duration.ofMillis(999)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("ipBlockWindow")
    }
}
