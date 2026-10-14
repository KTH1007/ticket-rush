package com.ticketrush.queue.application

import com.ticketrush.queue.domain.BindResult
import com.ticketrush.queue.domain.EventSchedule
import com.ticketrush.queue.domain.EventSchedulePort
import com.ticketrush.queue.domain.InvalidQueueTokenException
import com.ticketrush.queue.domain.QueueActiveExpiredException
import com.ticketrush.queue.domain.QueueClosedException
import com.ticketrush.queue.domain.QueueEventNotFoundException
import com.ticketrush.queue.domain.QueueNotActiveException
import com.ticketrush.queue.domain.QueueNotOpenException
import com.ticketrush.queue.domain.QueuePhoneMismatchException
import com.ticketrush.queue.domain.QueueRepositoryPort
import com.ticketrush.queue.domain.RegisterResult
import com.ticketrush.shared.AppTimeZone
import com.ticketrush.shared.PhoneHash
import com.ticketrush.shared.exception.BusinessException
import org.springframework.stereotype.Service
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
        val result = if (first is RegisterResult.ScheduleUnknown) queueRepository.register(eventId, token, loadSchedule(eventId)) else first
        return when (result) {
            is RegisterResult.Registered -> token
            is RegisterResult.NotOpen -> throw QueueNotOpenException(eventId, LocalDateTime.ofInstant(result.opensAt, AppTimeZone.ID))
            is RegisterResult.Closed -> throw QueueClosedException(eventId, LocalDateTime.ofInstant(result.startsAt, AppTimeZone.ID))
            RegisterResult.ScheduleUnknown -> error("공연 일정을 저장했는데도 찾지 못했습니다: eventId=$eventId")
        }
    }

    // 활성 토큰은 처음 쓴 전화번호에만 쓸 수 있다. 번호를 바꿔 가며 번호당 좌석 제한을 우회하는 것을 막는다
    fun admit(
        eventId: Long,
        token: String?,
        phoneHash: PhoneHash,
    ) {
        if (token == null) throw InvalidQueueTokenException()
        val rejection =
            when (queueRepository.bindPhone(eventId, token, phoneHash)) {
                BindResult.BOUND -> return
                BindResult.MISMATCH -> QueuePhoneMismatchException()
                BindResult.NOT_ACTIVE -> notActiveReason(eventId, token)
            }
        throw rejection
    }

    // 활성이 아닐 때만 한 번 더 읽어 이유를 가른다
    private fun notActiveReason(
        eventId: Long,
        token: String,
    ): BusinessException {
        val status = queueRepository.findStatus(eventId, token) ?: return InvalidQueueTokenException()
        return if (status.ended) QueueActiveExpiredException() else QueueNotActiveException()
    }

    // 등록은 로그인 같은 확인이 없어서 없는 이벤트 번호로 Redis 키가 남지 않게 여기서 막는다. Redis에 공연 일정이 없을 때만 DB를 본다
    private fun loadSchedule(eventId: Long): EventSchedule = eventSchedule.schedule(eventId) ?: throw QueueEventNotFoundException(eventId)
}
