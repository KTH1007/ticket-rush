package com.ticketrush.shared.exception

import java.time.Duration

@Suppress("AbstractClassCanBeConcreteClass")
abstract class ServiceUnavailableException(
    code: String,
    message: String,
    // 다시 시도해도 되는 때까지 남은 시간. 모르면 null
    val retryAfter: Duration? = null,
    cause: Throwable? = null,
) : BusinessException(code, message, cause)
