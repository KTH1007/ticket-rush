package com.ticketrush.queue.domain

import com.ticketrush.shared.exception.ServiceUnavailableException
import java.time.Duration

class QueueFullException(
    retryAfter: Duration,
) : ServiceUnavailableException(code = "QUEUE_FULL", message = "대기열이 가득 찼습니다. 잠시 후 다시 시도해주세요", retryAfter = retryAfter)
