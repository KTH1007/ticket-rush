package com.ticketrush.queue.application

import com.ticketrush.queue.domain.InvalidQueueTokenException
import com.ticketrush.queue.domain.QueueRepositoryPort
import com.ticketrush.queue.presentation.QueueStatusResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Duration

@Service
class QueueQueryService(
    private val queueRepository: QueueRepositoryPort,
    @Value("\${ticket-rush.queue.poll-interval.far}") private val farInterval: Duration,
    @Value("\${ticket-rush.queue.poll-interval.near}") private val nearInterval: Duration,
) {
    fun checkStatus(
        eventId: Long,
        token: String,
    ): QueueStatusResponse {
        val sequence = queueRepository.findSequence(eventId, token) ?: throw InvalidQueueTokenException()

        if (queueRepository.isActive(eventId, token)) {
            return QueueStatusResponse(rank = 0, nextPollIntervalMs = 0)
        }

        val lastPromoted = queueRepository.lastPromotedSequence(eventId)
        val rank = sequence - lastPromoted
        val interval = if (rank <= NEAR_THRESHOLD) nearInterval else farInterval
        return QueueStatusResponse(rank = rank, nextPollIntervalMs = interval.toMillis())
    }

    companion object {
        private const val NEAR_THRESHOLD = 100L
    }
}
