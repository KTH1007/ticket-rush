package com.ticketrush.outbox.application

import com.ticketrush.outbox.OutboxPolicyProperties
import com.ticketrush.outbox.domain.OutboxRepositoryPort
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test

// 느린 발송이 끝나기 전에 새 틱이 또 시작되면 같은 외부 시스템에 요청이 쌓인다. 한 인스턴스에서는 한 틱만 돈다
class OutboxRelaySchedulerTickGuardTest {
    private val outboxRepository = mockk<OutboxRepositoryPort>()
    private val relay =
        OutboxRelayScheduler(
            outboxRepository,
            mockk(),
            mockk(),
            mockk(),
            OutboxPolicyProperties(
                pollInterval = Duration.ofSeconds(1),
                chunkSize = 10,
                maxAttempts = 5,
                retryDelay = Duration.ofSeconds(30),
                staleClaimTimeout = Duration.ofSeconds(60),
                reclaimInterval = Duration.ofSeconds(30),
            ),
            Clock.systemUTC(),
        )

    @Test
    fun `앞 틱이 도는 동안 다시 불리면 건너뛰고 끝난 뒤에는 다시 돈다`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { outboxRepository.claimBatch(any(), any()) } answers {
            started.countDown()
            release.await(WAIT_SECONDS, TimeUnit.SECONDS)
            emptyList()
        } andThen emptyList()
        val first = thread { relay.relay() }
        started.await(WAIT_SECONDS, TimeUnit.SECONDS)

        relay.relay()
        verify(exactly = 1) { outboxRepository.claimBatch(any(), any()) }

        release.countDown()
        first.join()
        relay.relay()
        verify(exactly = 2) { outboxRepository.claimBatch(any(), any()) }
    }

    private companion object {
        const val WAIT_SECONDS = 5L
    }
}
