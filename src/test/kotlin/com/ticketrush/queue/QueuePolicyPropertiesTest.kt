package com.ticketrush.queue

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.time.Duration
import kotlin.test.Test

// 설정을 @Value로 흩어 읽을 때는 0이나 음수가 들어와도 기동이 되고 조용히 틀리게 동작했다. 기동 때 거부한다
class QueuePolicyPropertiesTest {
    private fun properties(
        tokenTtl: Duration = Duration.ofMinutes(15),
        activeTtl: Duration = Duration.ofMinutes(2),
        budget: Int = 100,
        far: Duration = Duration.ofSeconds(15),
        near: Duration = Duration.ofSeconds(2),
        nearRankThreshold: Long = 100,
        scheduleCacheTtl: Duration = Duration.ofMinutes(10),
    ) = QueuePolicyProperties(
        tokenTtl = tokenTtl,
        activeTtl = activeTtl,
        promotionBudgetPerSecond = budget,
        pollInterval = QueuePolicyProperties.PollInterval(far = far, near = near),
        nearRankThreshold = nearRankThreshold,
        scheduleCacheTtl = scheduleCacheTtl,
    )

    private fun assertRejected(
        field: String,
        create: () -> QueuePolicyProperties,
    ) {
        assertThatThrownBy { create() }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining(field)
    }

    @Test
    fun `정상 값이면 만들어진다`() {
        assertThat(properties().promotionBudgetPerSecond).isEqualTo(100)
    }

    @Test
    fun `가까운 순위 기준은 기본값이 100이다`() {
        val pollInterval = QueuePolicyProperties.PollInterval(far = Duration.ofSeconds(15), near = Duration.ofSeconds(2))
        val defaulted = QueuePolicyProperties(Duration.ofMinutes(15), Duration.ofMinutes(2), 100, pollInterval)

        assertThat(defaulted.nearRankThreshold).isEqualTo(100)
    }

    @Test
    fun `오픈 시각 캐시 TTL은 기본값이 10분이다`() {
        val pollInterval = QueuePolicyProperties.PollInterval(far = Duration.ofSeconds(15), near = Duration.ofSeconds(2))
        val defaulted = QueuePolicyProperties(Duration.ofMinutes(15), Duration.ofMinutes(2), 100, pollInterval)

        assertThat(defaulted.scheduleCacheTtl).isEqualTo(Duration.ofMinutes(10))
    }

    @Test
    fun `토큰 TTL이 0이나 음수면 거부한다`() {
        assertRejected("tokenTtl") { properties(tokenTtl = Duration.ZERO) }
        assertRejected("tokenTtl") { properties(tokenTtl = Duration.ofSeconds(-1)) }
    }

    @Test
    fun `활성 TTL이 0이나 음수면 거부한다`() {
        assertRejected("activeTtl") { properties(activeTtl = Duration.ZERO) }
    }

    @Test
    fun `초당 승격 예산이 0이나 음수면 거부한다`() {
        assertRejected("promotionBudgetPerSecond") { properties(budget = 0) }
        assertRejected("promotionBudgetPerSecond") { properties(budget = -5) }
    }

    @Test
    fun `폴링 간격이 0이나 음수면 거부한다`() {
        assertRejected("pollInterval.far") { properties(far = Duration.ZERO) }
        assertRejected("pollInterval.near") { properties(near = Duration.ZERO) }
    }

    // 가까운 순위가 먼 순위보다 더 드물게 폴링하면 순서가 거꾸로다
    @Test
    fun `가까운 순위의 폴링 간격이 먼 순위보다 길면 거부한다`() {
        assertRejected("pollInterval.near") { properties(far = Duration.ofSeconds(2), near = Duration.ofSeconds(15)) }
    }

    @Test
    fun `가까운 순위 기준이 0이나 음수면 거부한다`() {
        assertRejected("nearRankThreshold") { properties(nearRankThreshold = 0) }
    }

    @Test
    fun `오픈 시각 캐시 TTL이 0이나 음수면 거부한다`() {
        assertRejected("scheduleCacheTtl") { properties(scheduleCacheTtl = Duration.ZERO) }
        assertRejected("scheduleCacheTtl") { properties(scheduleCacheTtl = Duration.ofSeconds(-1)) }
    }
}
