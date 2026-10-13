package com.ticketrush.queue.application

import com.ticketrush.queue.QueuePolicyProperties
import com.ticketrush.queue.domain.InvalidQueueTokenException
import com.ticketrush.queue.domain.QueueNotActiveException
import com.ticketrush.queue.domain.QueueRepositoryPort
import com.ticketrush.queue.presentation.QueueStatusResponse
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.concurrent.ThreadLocalRandom

@Service
class QueueQueryService(
    private val queueRepository: QueueRepositoryPort,
    private val policy: QueuePolicyProperties,
) {
    fun checkStatus(
        eventId: Long,
        token: String,
    ): QueueStatusResponse {
        val status = queueRepository.findStatus(eventId, token) ?: throw InvalidQueueTokenException()

        if (status.active) {
            return QueueStatusResponse(rank = 0, nextPollIntervalMs = 0)
        }

        val rank = status.sequence - status.lastPromotedSequence
        val interval = nextPollInterval(rank)
        return QueueStatusResponse(rank = rank, nextPollIntervalMs = interval.toMillis())
    }

    fun requireActive(
        eventId: Long,
        token: String?,
    ) {
        val status = token?.let { queueRepository.findStatus(eventId, it) } ?: throw InvalidQueueTokenException()
        if (!status.active) throw QueueNotActiveException()
    }

    private fun nextPollInterval(rank: Long): Duration {
        val base = if (rank <= policy.nearRankThreshold) policy.pollInterval.near else farInterval(rank)
        return withJitter(base)
    }

    // 앞까지 걸리는 시간(순번 / 초당 승격 수)의 1/4마다 조회한다. far보다 짧아지지 않고 farMax를 넘지 않는다
    private fun farInterval(rank: Long): Duration =
        Duration
            .ofSeconds(rank / policy.promotionBudgetPerSecond / SECONDS_TO_FRONT_DIVISOR)
            .coerceIn(policy.pollInterval.far, policy.pollInterval.farMax)

    private fun withJitter(base: Duration): Duration {
        val ratio = policy.pollInterval.jitterRatio
        if (ratio == 0.0) return base
        return Duration.ofMillis((base.toMillis() * (1 + ThreadLocalRandom.current().nextDouble(-ratio, ratio))).toLong())
    }

    private companion object {
        const val SECONDS_TO_FRONT_DIVISOR = 4
    }
}
