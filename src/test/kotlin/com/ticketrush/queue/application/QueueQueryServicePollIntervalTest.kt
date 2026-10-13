package com.ticketrush.queue.application

import com.ticketrush.queue.QueuePolicyProperties
import com.ticketrush.queue.domain.QueueRepositoryPort
import com.ticketrush.queue.domain.QueueStatus
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import java.time.Duration
import kotlin.test.Test

class QueueQueryServicePollIntervalTest {
    private val queueRepository = mockk<QueueRepositoryPort>()

    private fun service(jitterRatio: Double = 0.0) =
        QueueQueryService(
            queueRepository,
            QueuePolicyProperties(
                tokenTtl = Duration.ofMinutes(15),
                activeTtl = Duration.ofMinutes(2),
                promotionBudgetPerSecond = 100,
                pollInterval =
                    QueuePolicyProperties.PollInterval(
                        far = Duration.ofSeconds(15),
                        near = Duration.ofSeconds(2),
                        farMax = Duration.ofMinutes(5),
                        jitterRatio = jitterRatio,
                    ),
            ),
        )

    private fun 대기_중(rank: Long) {
        every { queueRepository.findStatus(1L, "token") } returns QueueStatus(sequence = rank, active = false, lastPromotedSequence = 0)
    }

    @Test
    fun `가까운 순위는 near 간격이고 그 기준 바로 밖은 far 간격이다`() {
        대기_중(100)
        assertThat(service().checkStatus(1L, "token").nextPollIntervalMs).isEqualTo(2_000)

        대기_중(101)
        assertThat(service().checkStatus(1L, "token").nextPollIntervalMs).isEqualTo(15_000)
    }

    @Test
    fun `먼 순위는 앞까지 걸리는 시간의 4분의 1로 늘어나고 far와 farMax 사이로 제한된다`() {
        val expectedMsByRank =
            mapOf(
                3_000L to 15_000L,
                20_000L to 50_000L,
                100_000L to 250_000L,
                200_000L to 300_000L,
                500_000L to 300_000L,
            )

        expectedMsByRank.forEach { (rank, expectedMs) ->
            대기_중(rank)

            assertThat(service().checkStatus(1L, "token").nextPollIntervalMs).describedAs("순번 $rank").isEqualTo(expectedMs)
        }
    }

    @Test
    fun `지터를 켜면 간격이 비율 안에서 흩어진다`() {
        대기_중(3_000)

        val intervals = (1..300).map { service(jitterRatio = 0.2).checkStatus(1L, "token").nextPollIntervalMs }

        assertThat(intervals).allMatch { it in 12_000..18_000 }
        assertThat(intervals.toSet().size).isGreaterThan(1)
    }

    @Test
    fun `활성이 되면 순번 0과 간격 0을 돌려준다`() {
        every { queueRepository.findStatus(1L, "token") } returns QueueStatus(sequence = 10, active = true, lastPromotedSequence = 10)

        val response = service().checkStatus(1L, "token")

        assertThat(response.rank).isEqualTo(0)
        assertThat(response.nextPollIntervalMs).isEqualTo(0)
    }
}
