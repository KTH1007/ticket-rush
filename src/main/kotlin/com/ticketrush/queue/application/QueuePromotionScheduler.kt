package com.ticketrush.queue.application

import com.ticketrush.queue.QueuePolicyProperties
import com.ticketrush.queue.domain.QueueRepositoryPort
import com.ticketrush.queue.domain.SoldOutPort
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class QueuePromotionScheduler(
    private val queueRepository: QueueRepositoryPort,
    private val policy: QueuePolicyProperties,
    private val soldOutPort: SoldOutPort,
) {
    @Scheduled(
        fixedRateString = "\${ticket-rush.queue.promotion-interval}",
        initialDelayString = "\${ticket-rush.queue.promotion-interval}",
    )
    fun promote() {
        // 예산이 서비스 전체에 하나라, 순서가 고정이면 앞 공연이 예산을 다 가져가 뒤 공연이 굶는다. 매진된 공연은 빈 좌석 지도로 보내지 않는다
        queueRepository.activeEventIds().shuffled().filterNot(soldOutPort::isSoldOut).forEach { eventId ->
            queueRepository.promote(eventId, policy.promotionBudgetPerSecond)
        }
    }
}
