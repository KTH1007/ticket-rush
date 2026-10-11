package com.ticketrush.queue.application

import com.ticketrush.queue.QueuePolicyProperties
import com.ticketrush.queue.domain.InvalidQueueTokenException
import com.ticketrush.queue.domain.QueueNotActiveException
import com.ticketrush.queue.domain.QueueRepositoryPort
import com.ticketrush.queue.presentation.QueueStatusResponse
import org.springframework.stereotype.Service

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
        val interval = if (rank <= policy.nearRankThreshold) policy.pollInterval.near else policy.pollInterval.far
        return QueueStatusResponse(rank = rank, nextPollIntervalMs = interval.toMillis())
    }

    fun requireActive(
        eventId: Long,
        token: String?,
    ) {
        val status = token?.let { queueRepository.findStatus(eventId, it) } ?: throw InvalidQueueTokenException()
        if (!status.active) throw QueueNotActiveException()
    }
}
