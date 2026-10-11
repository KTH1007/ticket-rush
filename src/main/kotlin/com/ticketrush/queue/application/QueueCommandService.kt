package com.ticketrush.queue.application

import com.ticketrush.queue.domain.EventSchedulePort
import com.ticketrush.queue.domain.QueueEventNotFoundException
import com.ticketrush.queue.domain.QueueNotOpenException
import com.ticketrush.queue.domain.QueueRepositoryPort
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDateTime
import java.util.UUID

@Service
class QueueCommandService(
    private val queueRepository: QueueRepositoryPort,
    private val eventSchedule: EventSchedulePort,
    private val clock: Clock,
) {
    fun register(eventId: Long): String {
        requireRegistrable(eventId)
        val token = UUID.randomUUID().toString()
        queueRepository.register(eventId, token)
        return token
    }

    // 티켓팅은 같은 시각에 함께 들어와 시작하므로 오픈 전에는 줄을 세우지 않고(정각부터 허용), 등록은 로그인 같은 확인이 없어서 없는 이벤트 번호로 Redis 키가 남는 것도 막는다.
    // 이미 대기열이 열린 이벤트는 오픈 시각 이후에 등록이 통과한 것이라 DB를 보지 않는다
    private fun requireRegistrable(eventId: Long) {
        if (queueRepository.isActive(eventId)) return
        val opensAt = eventSchedule.opensAt(eventId) ?: throw QueueEventNotFoundException(eventId)
        if (LocalDateTime.now(clock).isBefore(opensAt)) throw QueueNotOpenException(eventId, opensAt)
    }
}
