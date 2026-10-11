package com.ticketrush.queue.application

import com.ticketrush.queue.domain.EventSchedulePort
import com.ticketrush.queue.domain.QueueEventNotFoundException
import com.ticketrush.queue.domain.QueueNotOpenException
import com.ticketrush.queue.domain.QueueRepositoryPort
import com.ticketrush.queue.domain.RegisterResult
import com.ticketrush.shared.AppTimeZone
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

@Service
class QueueCommandService(
    private val queueRepository: QueueRepositoryPort,
    private val eventSchedule: EventSchedulePort,
) {
    fun register(eventId: Long): String {
        val token = UUID.randomUUID().toString()
        val first = queueRepository.register(eventId, token)
        // 첫 호출이 ScheduleUnknown이면 스크립트가 아무것도 쓰지 않아서 같은 토큰으로 다시 불러도 된다
        val result = if (first is RegisterResult.ScheduleUnknown) queueRepository.register(eventId, token, loadOpensAt(eventId)) else first
        return when (result) {
            is RegisterResult.Registered -> token
            is RegisterResult.NotOpen -> throw QueueNotOpenException(eventId, LocalDateTime.ofInstant(result.opensAt, AppTimeZone.ID))
            RegisterResult.ScheduleUnknown -> error("오픈 시각을 저장했는데도 찾지 못했습니다: eventId=$eventId")
        }
    }

    // 등록은 로그인 같은 확인이 없어서 없는 이벤트 번호로 Redis 키가 남지 않게 여기서 막는다. Redis에 오픈 시각이 없을 때만 DB를 본다
    private fun loadOpensAt(eventId: Long): Instant = eventSchedule.opensAt(eventId) ?: throw QueueEventNotFoundException(eventId)
}
