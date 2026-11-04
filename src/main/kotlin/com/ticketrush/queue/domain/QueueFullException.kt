package com.ticketrush.queue.domain

import com.ticketrush.shared.exception.ServiceUnavailableException
import java.time.Duration

private val REUSABLE_FOR = Duration.ofSeconds(1)

// 가득 찬 대기열의 거절은 요청자와 무관하게 같아서, 폭주 때 앞단(nginx)이 앱까지 보내지 않고 잠깐 같은 응답을 돌려줄 수 있다
class QueueFullException(
    retryAfter: Duration,
) : ServiceUnavailableException(
        code = "QUEUE_FULL",
        message = "대기열이 가득 찼습니다. 잠시 후 다시 시도해주세요",
        retryAfter = retryAfter,
        reusableFor = REUSABLE_FOR,
    )
