package com.ticketrush.shared

import java.util.concurrent.atomic.AtomicBoolean

// 같은 인스턴스에서 같은 스케줄러의 틱이 겹치지 않게 한다. 돌고 있으면 이번 틱은 건너뛴다
class TickGuard {
    private val running = AtomicBoolean(false)

    fun runIfIdle(block: () -> Unit) {
        if (!running.compareAndSet(false, true)) return
        try {
            block()
        } finally {
            running.set(false)
        }
    }
}
