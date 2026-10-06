package com.ticketrush.payment

import org.assertj.core.api.Assertions.assertThat
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

    @Test
    fun `환불 재시도 설정을 생략하면 기본값은 5분 간격에 최대 5회다`() {
        val policy = PaymentPolicyProperties(staleClaimTimeout = Duration.ofSeconds(10), reclaimInterval = Duration.ofSeconds(5))

        assertThat(policy.refundRetryDelay).isEqualTo(Duration.ofMinutes(5))
        assertThat(policy.refundMaxAttempts).isEqualTo(5)
    }

    @Test
    fun `refundRetryDelay가 0 이하면 예외가 난다`() {
        assertThatThrownBy {
            PaymentPolicyProperties(
                staleClaimTimeout = Duration.ofSeconds(10),
                reclaimInterval = Duration.ofSeconds(5),
                refundRetryDelay = Duration.ZERO,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `refundMaxAttempts가 0 이하면 예외가 난다`() {
        assertThatThrownBy {
            PaymentPolicyProperties(
                staleClaimTimeout = Duration.ofSeconds(10),
                reclaimInterval = Duration.ofSeconds(5),
                refundMaxAttempts = 0,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
