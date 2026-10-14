package com.ticketrush.queue.domain

data class QueueStatus(
    val sequence: Long,
    val active: Boolean,
    val lastPromotedSequence: Long,
) {
    // 승격 순번을 지났는데 활성이 아니면 활성 구간이 끝난 토큰이다
    val ended: Boolean get() = !active && sequence <= lastPromotedSequence
}
