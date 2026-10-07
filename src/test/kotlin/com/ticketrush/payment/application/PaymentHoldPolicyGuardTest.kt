package com.ticketrush.payment.application

import com.ticketrush.payment.PaymentPolicyProperties
import com.ticketrush.reservation.SeatPolicyProperties
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.time.Duration
import kotlin.test.Test

class PaymentHoldPolicyGuardTest {
    private fun guard(
        holdTtl: Duration,
        paymentFailedHoldTtl: Duration,
        minHoldRemaining: Duration,
    ) = PaymentHoldPolicyGuard(
        PaymentPolicyProperties(
            staleClaimTimeout = Duration.ofSeconds(10),
            reclaimInterval = Duration.ofSeconds(5),
            minHoldRemaining = minHoldRemaining,
        ),
        SeatPolicyProperties(holdTtl = holdTtl, maxPerPhone = 2, paymentFailedHoldTtl = paymentFailedHoldTtl),
    )

    @Test
    fun `기본값처럼 거절 뒤 홀드가 하한보다 길면 통과한다`() {
        assertThatCode { guard(Duration.ofMinutes(5), Duration.ofMinutes(1), Duration.ofSeconds(15)) }.doesNotThrowAnyException()
    }

    // 거절 뒤 홀드가 하한 이하면 재시도하려는 순간 이미 하한에 걸려, 설정만 바꿔도 모든 재시도가 조용히 막힌다
    @Test
    fun `거절 뒤 홀드가 하한 이하이면 기동에 실패한다`() {
        assertThatThrownBy { guard(Duration.ofMinutes(5), Duration.ofSeconds(15), Duration.ofSeconds(15)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("paymentFailedHoldTtl")
    }

    @Test
    fun `처음 홀드가 하한 이하이면 기동에 실패한다`() {
        assertThatThrownBy { guard(Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofSeconds(10)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("holdTtl")
    }
}
