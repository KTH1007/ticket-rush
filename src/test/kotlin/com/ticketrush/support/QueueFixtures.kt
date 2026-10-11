package com.ticketrush.support

import com.ticketrush.queue.domain.QueueRepositoryPort
import com.ticketrush.queue.domain.RegisterResult
import java.time.Instant

// 대기열 동작을 보는 테스트가 오픈 판정에 신경 쓰지 않도록, 오픈 시각을 아주 먼 과거로 채워 이미 열린 공연처럼 등록한다
fun QueueRepositoryPort.열린_공연에_등록(
    eventId: Long,
    token: String,
): Long = (register(eventId, token, Instant.EPOCH) as RegisterResult.Registered).sequence
