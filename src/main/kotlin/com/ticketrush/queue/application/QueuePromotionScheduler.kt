package com.ticketrush.queue.application

import com.ticketrush.queue.QueuePolicyProperties
import com.ticketrush.queue.domain.QueueRepositoryPort
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class QueuePromotionScheduler(
    private val queueRepository: QueueRepositoryPort,
    private val policy: QueuePolicyProperties,
) {
    @Scheduled(fixedRate = 1000)
    fun promote() {
        queueRepository.activeEventIds().forEach { eventId ->
            val granted = queueRepository.reserveGlobalBudget(policy.promotionBudgetPerSecond)
            if (granted > 0) {
                queueRepository.promote(eventId, granted)
            }
        }
    }
}
