package com.ticketrush.queue.application

import com.ticketrush.queue.domain.EventExistencePort
import com.ticketrush.queue.domain.QueueEventNotFoundException
import com.ticketrush.queue.domain.QueueRepositoryPort
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class QueueCommandService(
    private val queueRepository: QueueRepositoryPort,
    private val eventExistence: EventExistencePort,
) {
    fun register(eventId: Long): String {
        requireEventExists(eventId)
        val token = UUID.randomUUID().toString()
        queueRepository.register(eventId, token)
        return token
    }

    // 등록은 로그인 같은 확인이 없어서, 없는 이벤트 번호마다 Redis 키가 남지 않게 막는다. 이미 대기열이 열린 이벤트는 존재 확인을 통과한 것이라 DB를 보지 않는다
    private fun requireEventExists(eventId: Long) {
        if (queueRepository.isActive(eventId)) return
        if (!eventExistence.exists(eventId)) throw QueueEventNotFoundException(eventId)
    }
}
