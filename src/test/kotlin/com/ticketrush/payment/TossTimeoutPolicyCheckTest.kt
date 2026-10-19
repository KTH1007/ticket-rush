package com.ticketrush.payment

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Duration
import kotlin.test.Test

class TossTimeoutPolicyCheckTest {
    private fun toss(
        connect: Duration = Duration.ofSeconds(3),
        read: Duration = Duration.ofSeconds(3),
    ) = TossProperties(baseUrl = "http://localhost", secretKey = "k", connectTimeout = connect, readTimeout = read)

    private fun payment(
        stale: Duration = Duration.ofSeconds(10),
        minHold: Duration = Duration.ofSeconds(15),
    ) = PaymentPolicyProperties(staleClaimTimeout = stale, reclaimInterval = Duration.ofSeconds(5), minHoldRemaining = minHold)

    @Test
    fun `기본값은 통과한다`() {
        assertThatCode { TossTimeoutPolicyCheck(toss(), payment()) }.doesNotThrowAnyException()
    }

    @Test
    fun `경계값(이어받기 시간이 호출 한 번과 같고 남은 홀드 하한이 두 번과 같음)은 통과한다`() {
        assertThatCode { TossTimeoutPolicyCheck(toss(), payment(stale = Duration.ofSeconds(6), minHold = Duration.ofSeconds(12))) }
            .doesNotThrowAnyException()
    }

    @Test
    fun `이어받기 시간이 호출 한 번의 최악보다 짧으면 거부한다`() {
        assertThatThrownBy { TossTimeoutPolicyCheck(toss(), payment(stale = Duration.ofSeconds(5))) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("staleClaimTimeout")
    }

    @Test
    fun `Toss 타임아웃을 늘리고 이어받기 시간을 그대로 두면 거부한다`() {
        assertThatThrownBy { TossTimeoutPolicyCheck(toss(read = Duration.ofSeconds(8)), payment()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("staleClaimTimeout")
    }

    @Test
    fun `남은 홀드 하한이 호출 두 번의 최악보다 짧으면 거부한다`() {
        assertThatThrownBy { TossTimeoutPolicyCheck(toss(), payment(minHold = Duration.ofSeconds(11))) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("minHoldRemaining")
    }

    // 설정에 따른 빈 등록까지 확인한다. gateway가 toss가 아니면 점검 자체가 없다
    private fun runner(gateway: String) =
        ApplicationContextRunner()
            .withPropertyValues("$PAYMENT_GATEWAY_PROPERTY=$gateway")
            .withUserConfiguration(TossTimeoutPolicyCheck::class.java)
            .withBean(TossProperties::class.java, { toss() })

    @Test
    fun `gateway가 toss일 때 설정이 어긋나면 컨텍스트가 뜨지 않는다`() {
        runner("toss")
            .withBean(PaymentPolicyProperties::class.java, { payment(stale = Duration.ofSeconds(5)) })
            .run { assertThat(it).hasFailed() }
    }

    @Test
    fun `gateway가 toss일 때 기본값이면 점검이 등록된다`() {
        runner("toss")
            .withBean(PaymentPolicyProperties::class.java, { payment() })
            .run { assertThat(it).hasSingleBean(TossTimeoutPolicyCheck::class.java) }
    }

    @Test
    fun `gateway가 fake이면 점검이 등록되지 않는다`() {
        runner("fake")
            .withBean(PaymentPolicyProperties::class.java, { payment(stale = Duration.ofSeconds(5)) })
            .run { assertThat(it).doesNotHaveBean(TossTimeoutPolicyCheck::class.java) }
    }
}
