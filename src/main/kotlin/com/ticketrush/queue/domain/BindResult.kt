package com.ticketrush.queue.domain

enum class BindResult {
    // 새로 묶였거나 이미 같은 번호에 묶여 있음
    BOUND,
    MISMATCH,
    NOT_ACTIVE,
}
