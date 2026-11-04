package com.ticketrush.shared.exception

import java.time.Duration

@Suppress("AbstractClassCanBeConcreteClass")
abstract class ServiceUnavailableException(
    code: String,
    message: String,
    // 다시 시도해도 되는 때까지 남은 시간. 모르면 null
    val retryAfter: Duration? = null,
    cause: Throwable? = null,
    // 앞단 캐시가 같은 응답을 이 시간 동안 다시 써도 되는 거절. 요청마다 달라지는 거절이면 null
    val reusableFor: Duration? = null,
) : BusinessException(code, message, cause)
