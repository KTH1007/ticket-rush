package com.ticketrush.queue.application

import com.ticketrush.queue.domain.QueueRepositoryPort
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class QueueCommandService(
    private val queueRepository: QueueRepositoryPort,
) {
    fun register(eventId: Long): String {
        val token = UUID.randomUUID().toString()
        queueRepository.register(eventId, token)
        return token
    }
}
