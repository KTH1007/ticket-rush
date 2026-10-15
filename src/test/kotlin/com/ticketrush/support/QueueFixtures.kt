package com.ticketrush.support

import com.ticketrush.queue.domain.EventSchedule
import com.ticketrush.queue.domain.QueueRepositoryPort
import com.ticketrush.queue.domain.RegisterResult
import com.ticketrush.shared.ClientIp
import java.time.Instant

private val farFuture: Instant = Instant.parse("9999-01-01T00:00:00Z")

// IP 한도를 거는 테스트가 아니면 같은 IP를 쓴다. 테스트 설정이 한도를 크게 올려 둔다
val testClientIp = ClientIp("203.0.113.1")

// 대기열 동작을 보는 테스트가 오픈 판정에 신경 쓰지 않도록, 오픈 시각을 아주 먼 과거로 시작 시각을 아주 먼 미래로 채워 판매 중인 공연처럼 등록한다
fun QueueRepositoryPort.열린_공연에_등록(
    eventId: Long,
    token: String,
): Long =
    (
        register(
            eventId,
            token,
            testClientIp,
            EventSchedule(opensAt = Instant.EPOCH, startsAt = farFuture),
        ) as RegisterResult.Registered
    ).sequence
