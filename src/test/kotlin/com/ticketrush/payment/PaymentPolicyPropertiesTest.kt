package com.ticketrush.payment

import org.assertj.core.api.Assertions.assertThatThrownBy
import java.time.Duration
import kotlin.test.Test

class PaymentPolicyPropertiesTest {
    @Test
    fun `staleClaimTimeout이 0 이하면 예외가 난다`() {
        assertThatThrownBy {
            PaymentPolicyProperties(staleClaimTimeout = Duration.ZERO, reclaimInterval = Duration.ofSeconds(30))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `reclaimInterval이 음수면 예외가 난다`() {
        assertThatThrownBy {
            PaymentPolicyProperties(staleClaimTimeout = Duration.ofSeconds(10), reclaimInterval = Duration.ofSeconds(-1))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
