package com.ticketrush.reservation.infrastructure

import com.ticketrush.queue.QueuePolicyProperties
import com.ticketrush.queue.domain.SoldOutPort
import com.ticketrush.reservation.domain.SeatQueryPort
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

// 대기열 모듈이 정의한 포트의 구현. 의존 방향을 reservation -> queue로 두어 모듈 순환을 피한다
@Component
class SeatSoldOutAdapter(
    private val seatQueryPort: SeatQueryPort,
    private val clock: Clock,
    private val policy: QueuePolicyProperties,
) : SoldOutPort {
    private data class Checked(
        val at: Instant,
        val soldOut: Boolean,
    )

    private val cache = ConcurrentHashMap<Long, Checked>()

    // 승격 스케줄러와 순번 조회가 자주 묻는다. compute로 같은 공연의 동시 요청이 DB를 한 번만 보게 한다
    override fun isSoldOut(eventId: Long): Boolean {
        val now = clock.instant()
        val checked =
            cache.compute(eventId) { _, cached ->
                if (cached != null &&
                    cached.at.plus(policy.broadcastCacheTtl) > now
                ) {
                    cached
                } else {
                    Checked(now, seatQueryPort.isFullySold(eventId))
                }
            }
        return checkNotNull(checked).soldOut
    }
}
