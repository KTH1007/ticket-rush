package com.ticketrush.reservation

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.time.Duration
import kotlin.test.Test

class SeatPolicyPropertiesTest {
    @Test
    fun `holdTtl이 양수이고 maxPerPhone이 1~2고 paymentFailedHoldTtl이 holdTtl보다 짧으면 만들 수 있다`() {
        // when
        val policy = 정책(holdTtl = Duration.ofMinutes(5), maxPerPhone = 2, paymentFailedHoldTtl = Duration.ofMinutes(1))

        // then
        assertThat(policy.maxPerPhone).isEqualTo(2)
    }

    @Test
    fun `holdTtl이 0이면 만들 수 없다`() {
        // when & then
        assertThatThrownBy { 정책(holdTtl = Duration.ZERO, maxPerPhone = 1) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `holdTtl이 음수면 만들 수 없다`() {
        // when & then
        assertThatThrownBy { 정책(holdTtl = Duration.ofMinutes(-1), maxPerPhone = 1) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `maxPerPhone이 0이면 만들 수 없다`() {
        // when & then
        assertThatThrownBy { 정책(maxPerPhone = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `maxPerPhone이 3 이상이면 만들 수 없다`() {
        // when & then: DB CHECK(slot_no IN (1, 2))가 2까지만 허용하므로 3 이상은 부팅 시점에 막아야 한다
        assertThatThrownBy { 정책(maxPerPhone = 3) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `paymentFailedHoldTtl이 0이면 만들 수 없다`() {
        // when & then
        assertThatThrownBy { 정책(paymentFailedHoldTtl = Duration.ZERO) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `paymentFailedHoldTtl이 holdTtl보다 길거나 같으면 만들 수 없다`() {
        // when & then
        assertThatThrownBy { 정책(holdTtl = Duration.ofMinutes(5), paymentFailedHoldTtl = Duration.ofMinutes(5)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `soldMarkTtl이 holdTtl과 같으면 만들 수 있다`() {
        // when
        val policy = 정책(holdTtl = Duration.ofMinutes(5), soldMarkTtl = Duration.ofMinutes(5))

        // then
        assertThat(policy.soldMarkTtl).isEqualTo(Duration.ofMinutes(5))
    }

    @Test
    fun `soldMarkTtl이 holdTtl보다 짧으면 만들 수 없다`() {
        // when & then
        assertThatThrownBy { 정책(holdTtl = Duration.ofMinutes(5), soldMarkTtl = Duration.ofMinutes(4)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("soldMarkTtl")
    }

    private fun 정책(
        holdTtl: Duration = Duration.ofMinutes(5),
        maxPerPhone: Int = 2,
        paymentFailedHoldTtl: Duration = Duration.ofMinutes(1),
        soldMarkTtl: Duration = Duration.ofHours(1),
        holdExpiryInterval: Duration = Duration.ofSeconds(30),
    ): SeatPolicyProperties =
        SeatPolicyProperties(
            holdTtl = holdTtl,
            maxPerPhone = maxPerPhone,
            paymentFailedHoldTtl = paymentFailedHoldTtl,
            soldMarkTtl = soldMarkTtl,
            holdExpiryInterval = holdExpiryInterval,
        )

    @Test
    fun `홀드 만료 주기를 생략하면 기본값은 30초다`() {
        assertThat(SeatPolicyProperties(Duration.ofMinutes(5), 2, Duration.ofMinutes(1), Duration.ofHours(1)).holdExpiryInterval)
            .isEqualTo(Duration.ofSeconds(30))
    }

    @Test
    fun `holdExpiryInterval이 0 이하면 만들 수 없다`() {
        assertThatThrownBy { 정책(holdExpiryInterval = Duration.ZERO) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { 정책(holdExpiryInterval = Duration.ofSeconds(-1)) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
