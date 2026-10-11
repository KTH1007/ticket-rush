package com.ticketrush.queue.application

import com.ticketrush.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor
import org.springframework.scheduling.config.FixedRateTask
import java.time.Duration
import kotlin.test.Test

class QueuePromotionSchedulerConfigTest : IntegrationTest() {
    @Autowired
    lateinit var scheduledProcessor: ScheduledAnnotationBeanPostProcessor

    @Test
    fun `승격 스케줄러의 주기는 설정에서 읽고 첫 틱도 한 주기 늦춘다`() {
        val task =
            scheduledProcessor.scheduledTasks
                .map { it.task }
                .filterIsInstance<FixedRateTask>()
                .single { it.runnable.toString() == "${QueuePromotionScheduler::class.java.name}.promote" }

        assertThat(task.intervalDuration).isEqualTo(Duration.ofHours(1))
        assertThat(task.initialDelayDuration).isEqualTo(Duration.ofHours(1))
    }
}
