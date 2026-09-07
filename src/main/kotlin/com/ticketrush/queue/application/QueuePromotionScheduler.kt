package com.ticketrush.queue.application

import com.ticketrush.queue.domain.QueueRepositoryPort
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class QueuePromotionScheduler(
    private val queueRepository: QueueRepositoryPort,
    @Value("\${ticket-rush.queue.promotion-budget-per-second}") private val budgetPerSecond: Int,
) {
    @Scheduled(fixedRate = 1000)
    fun promote() {
        queueRepository.activeEventIds().forEach { eventId ->
            val granted = queueRepository.reserveGlobalBudget(budgetPerSecond)
            if (granted > 0) {
                queueRepository.promote(eventId, granted)
            }
        }
    }
}
