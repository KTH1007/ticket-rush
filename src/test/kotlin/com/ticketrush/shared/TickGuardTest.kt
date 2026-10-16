package com.ticketrush.shared

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test

class TickGuardTest {
    private val guard = TickGuard()

    @Test
    fun `돌고 있지 않으면 실행한다`() {
        val runs = AtomicInteger()

        guard.runIfIdle { runs.incrementAndGet() }

        assertThat(runs.get()).isEqualTo(1)
    }

    @Test
    fun `다른 스레드에서 돌고 있는 동안 다시 부르면 건너뛰고, 끝난 뒤에는 다시 실행한다`() {
        val runs = AtomicInteger()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val running =
            thread {
                guard.runIfIdle {
                    runs.incrementAndGet()
                    started.countDown()
                    release.await(WAIT_SECONDS, TimeUnit.SECONDS)
                }
            }
        assertThat(started.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue()

        guard.runIfIdle { runs.incrementAndGet() }
        assertThat(runs.get()).isEqualTo(1)

        release.countDown()
        running.join()
        guard.runIfIdle { runs.incrementAndGet() }
        assertThat(runs.get()).isEqualTo(2)
    }

    @Test
    fun `실행 중 예외가 나도 가드가 풀려 다음 틱이 실행된다`() {
        val runs = AtomicInteger()

        assertThatThrownBy { guard.runIfIdle { error("틱 실패") } }.isInstanceOf(IllegalStateException::class.java)
        guard.runIfIdle { runs.incrementAndGet() }

        assertThat(runs.get()).isEqualTo(1)
    }

    private companion object {
        const val WAIT_SECONDS = 5L
    }
}
