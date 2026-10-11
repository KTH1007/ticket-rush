package com.ticketrush.event.application

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class SeatStatusRefresher(
    private val cache: SeatStatusSnapshotCache,
) {
    // fixedDelay는 앞 갱신이 끝난 뒤 한 주기를 기다려서 가상 스레드 스케줄러에서도 틱이 겹치지 않는다
    @Scheduled(
        fixedDelayString = "\${ticket-rush.seat-map.status-refresh-interval}",
        initialDelayString = "\${ticket-rush.seat-map.status-refresh-interval}",
    )
    fun refresh() = cache.refreshRecentlyRead()
}
