package com.ticketrush.queue.domain

import com.ticketrush.shared.ClientIp
import com.ticketrush.shared.PhoneHash

interface QueueRepositoryPort {
    // 오픈, 시작 판정과 IP별 발급 상한 확인, 순번 부여를 한 번에 한다. scheduleHint는 Redis에 공연 일정이 없을 때만 저장된다
    fun register(
        eventId: Long,
        token: String,
        clientIp: ClientIp,
        scheduleHint: EventSchedule? = null,
    ): RegisterResult

    // count는 이번 호출의 최대치다. 서비스 전체의 초당 승격 예산을 넘지 않도록 스크립트가 줄인다
    fun promote(
        eventId: Long,
        count: Int,
    ): List<String>

    fun findStatus(
        eventId: Long,
        token: String,
    ): QueueStatus?

    // 활성 토큰을 처음 쓰는 전화번호에 묶는다. 이미 묶여 있으면 같은 번호인지만 본다
    fun bindPhone(
        eventId: Long,
        token: String,
        phoneHash: PhoneHash,
    ): BindResult

    fun activeEventIds(): Set<Long>
}
