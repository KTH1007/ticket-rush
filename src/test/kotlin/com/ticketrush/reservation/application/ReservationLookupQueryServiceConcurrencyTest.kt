package com.ticketrush.reservation.application

import com.ticketrush.reservation.ReservationLookupPolicyProperties
import com.ticketrush.reservation.domain.ReservationLookupFailedException
import com.ticketrush.reservation.domain.ReservationLookupRateLimitedException
import com.ticketrush.reservation.domain.ReservationLookupResult
import com.ticketrush.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// 확인(카운트 조회)과 예약(카운트 증가)이 분리돼 있으면 동시 요청이 전부 stale한 값을
// 읽고 통과할 수 있다(TOCTOU). 임계치보다 훨씬 많은 요청을 동시에 보내도 실제로 DB까지
// 도달해서 "조회를 시도한" 횟수가 임계치를 절대 넘지 않는지 확인한다.
@Tag("concurrency")
class ReservationLookupQueryServiceConcurrencyTest : IntegrationTest() {
    @Autowired
    lateinit var queryService: ReservationLookupQueryService

    @Autowired
    lateinit var policy: ReservationLookupPolicyProperties

    @RepeatedTest(5)
    fun `동시에 여러 번 실패해도 실제로 조회가 실행되는 횟수는 임계치를 넘지 않는다`() {
        // given
        val reservationNo = "RACE${System.nanoTime()}"
        val threadCount = policy.maxAttempts * 4

        // when
        val results = 동시_조회_시도(reservationNo, threadCount)

        // then
        val actuallyChecked = results.count { it.exceptionOrNull() is ReservationLookupFailedException }
        val blocked = results.count { it.exceptionOrNull() is ReservationLookupRateLimitedException }
        assertThat(actuallyChecked).isEqualTo(policy.maxAttempts)
        assertThat(blocked).isEqualTo(threadCount - policy.maxAttempts)
    }

    private fun 동시_조회_시도(
        reservationNo: String,
        threadCount: Int,
    ): List<Result<ReservationLookupResult>> {
        val startGate = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)
        val results = Collections.synchronizedList(mutableListOf<Result<ReservationLookupResult>>())

        try {
            repeat(threadCount) {
                executor.submit {
                    startGate.await()
                    results.add(runCatching { queryService.lookup(reservationNo, "01099999999") })
                    doneLatch.countDown()
                }
            }
            startGate.countDown()
            check(doneLatch.await(10, TimeUnit.SECONDS)) { "조회 스레드가 10초 안에 끝나지 않았다" }
        } finally {
            executor.shutdownNow()
        }
        return results
    }
}
