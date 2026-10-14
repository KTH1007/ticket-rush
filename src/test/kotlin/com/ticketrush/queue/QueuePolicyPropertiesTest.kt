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
        farMax: Duration = Duration.ofMinutes(5),
        jitterRatio: Double = 0.2,
        nearRankThreshold: Long = 100,
        scheduleCacheTtl: Duration = Duration.ofMinutes(10),
    ) = QueuePolicyProperties(
        tokenTtl = tokenTtl,
        activeTtl = activeTtl,
        promotionBudgetPerSecond = budget,
        pollInterval = QueuePolicyProperties.PollInterval(far = far, near = near, farMax = farMax, jitterRatio = jitterRatio),
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

    // 1초 미만은 초 단위로 바꿀 때 0이 되고, Redis가 EX 0을 거절해 첫 등록이나 첫 승격에서 터진다
    @Test
    fun `Redis에 유효시간으로 넘기는 값은 1초 미만이면 거부한다`() {
        assertRejected("tokenTtl") { properties(tokenTtl = Duration.ofMillis(500)) }
        assertRejected("activeTtl") { properties(activeTtl = Duration.ofMillis(500)) }
        assertRejected("scheduleCacheTtl") { properties(scheduleCacheTtl = Duration.ofMillis(500)) }
    }

    @Test
    fun `Redis에 유효시간으로 넘기는 값이 정확히 1초면 만들어진다`() {
        val oneSecond = Duration.ofSeconds(1)

        val created =
            properties(
                tokenTtl = oneSecond,
                activeTtl = oneSecond,
                scheduleCacheTtl = oneSecond,
                near = Duration.ofMillis(50),
                far = Duration.ofMillis(100),
                farMax = Duration.ofMillis(200),
            )

        assertThat(created.tokenTtl).isEqualTo(oneSecond)
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
    fun `조회 간격 상한과 지터의 기본값은 5분과 0점2다`() {
        val pollInterval = QueuePolicyProperties.PollInterval(far = Duration.ofSeconds(15), near = Duration.ofSeconds(2))

        assertThat(pollInterval.farMax).isEqualTo(Duration.ofMinutes(5))
        assertThat(pollInterval.jitterRatio).isEqualTo(0.2)
    }

    @Test
    fun `조회 간격 상한이 하한보다 짧으면 거부한다`() {
        assertRejected("pollInterval.farMax") { properties(far = Duration.ofSeconds(15), farMax = Duration.ofSeconds(10)) }
    }

    // 조회 간격이 토큰 수명을 넘으면 다음 조회 전에 대기 토큰이 만료된다
    @Test
    fun `조회 간격 상한이 지터를 더해 토큰 수명 이상이면 거부한다`() {
        assertRejected("pollInterval.farMax") { properties(tokenTtl = Duration.ofMinutes(5), farMax = Duration.ofMinutes(5)) }
        assertRejected("pollInterval.farMax") { properties(tokenTtl = Duration.ofMinutes(5), farMax = Duration.ofSeconds(290)) }
    }

    // Redis에는 초 단위로 잘려 전달돼 1100ms는 1초 뒤에 만료된다
    @Test
    fun `조회 간격 상한이 초 단위로 자른 토큰 수명보다 길면 거부한다`() {
        assertRejected("pollInterval.farMax") {
            properties(
                tokenTtl = Duration.ofMillis(1_100),
                near = Duration.ofMillis(50),
                far = Duration.ofMillis(100),
                farMax = Duration.ofMillis(1_050),
                jitterRatio = 0.0,
            )
        }
    }

    @Test
    fun `지터 비율이 0과 1 사이를 벗어나면 거부한다`() {
        assertRejected("jitterRatio") { properties(jitterRatio = -0.1) }
        assertRejected("jitterRatio") { properties(jitterRatio = 1.1) }
    }

    @Test
    fun `오픈 시각 캐시 TTL이 0이나 음수면 거부한다`() {
        assertRejected("scheduleCacheTtl") { properties(scheduleCacheTtl = Duration.ZERO) }
        assertRejected("scheduleCacheTtl") { properties(scheduleCacheTtl = Duration.ofSeconds(-1)) }
    }
}
