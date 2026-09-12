package com.ticketrush.reservation

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.time.Duration
import kotlin.test.Test

class SeatPolicyPropertiesTest {
    @Test
    fun `holdTtl이 양수이고 maxPerPhone이 1~2면 만들 수 있다`() {
        // when
        val policy = SeatPolicyProperties(holdTtl = Duration.ofMinutes(5), maxPerPhone = 2)

        // then
        assertThat(policy.maxPerPhone).isEqualTo(2)
    }

    @Test
    fun `holdTtl이 0이면 만들 수 없다`() {
        // when & then
        assertThatThrownBy { SeatPolicyProperties(holdTtl = Duration.ZERO, maxPerPhone = 1) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `holdTtl이 음수면 만들 수 없다`() {
        // when & then
        assertThatThrownBy { SeatPolicyProperties(holdTtl = Duration.ofMinutes(-1), maxPerPhone = 1) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `maxPerPhone이 0이면 만들 수 없다`() {
        // when & then
        assertThatThrownBy { SeatPolicyProperties(holdTtl = Duration.ofMinutes(5), maxPerPhone = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `maxPerPhone이 3 이상이면 만들 수 없다`() {
        // when & then: DB CHECK(slot_no IN (1, 2))가 2까지만 허용하므로 3 이상은 부팅 시점에 막아야 한다
        assertThatThrownBy { SeatPolicyProperties(holdTtl = Duration.ofMinutes(5), maxPerPhone = 3) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
