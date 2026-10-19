package com.ticketrush

import com.ticketrush.payment.PaymentPolicyProperties
import com.ticketrush.reservation.SeatPolicyProperties
import com.ticketrush.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor
import org.springframework.scheduling.config.FixedDelayTask
import org.springframework.scheduling.config.FixedRateTask
import kotlin.test.Test

class ScheduledTasksPolicyTest : IntegrationTest() {
    @Autowired
    lateinit var scheduledProcessor: ScheduledAnnotationBeanPostProcessor

    @Autowired
    lateinit var seatPolicy: SeatPolicyProperties

    @Autowired
    lateinit var paymentPolicy: PaymentPolicyProperties

    // 가상 스레드 스케줄러에서 fixedDelay와 Trigger 작업은 한 스레드를 공유해 서로 직렬화된다. 느린 작업 하나가 다른 작업을 막는다
    @Test
    fun `fixedDelay 작업은 없다`() {
        val tasks = scheduledProcessor.scheduledTasks.map { it.task }

        assertThat(tasks.filterIsInstance<FixedDelayTask>()).isEmpty()
    }

    @Test
    fun `홀드 만료 주기는 설정에서 읽는다`() {
        assertThat(fixedRate("HoldExpiryScheduler", "sweep").intervalDuration).isEqualTo(seatPolicy.holdExpiryInterval)
    }

    @Test
    fun `환불 재시도 주기는 회수 주기와 별도의 설정에서 읽는다`() {
        assertThat(fixedRate("PaymentRefundRetryScheduler", "retry").intervalDuration).isEqualTo(paymentPolicy.refundRetryInterval)
        assertThat(fixedRate("PaymentReclaimScheduler", "reclaim").intervalDuration).isEqualTo(paymentPolicy.reclaimInterval)
        assertThat(paymentPolicy.refundRetryInterval).isNotEqualTo(paymentPolicy.reclaimInterval)
    }

    // MeterBinder 빈이 너무 일찍 만들어지면 @Scheduled가 조용히 빠질 수 있어, 등록됐는지와 주기를 같이 확인한다
    @Test
    fun `환불 미처리 건수 갱신 주기는 설정에서 읽는다`() {
        val task = fixedRate("PaymentRefundBacklogMetrics", "refresh")

        assertThat(task.intervalDuration).isEqualTo(paymentPolicy.refundBacklogRefreshInterval)
    }

    // 프록시가 씌워진 빈은 클래스 이름 뒤에 접미사가 붙을 수 있어 이름의 앞부분과 메서드 이름으로 찾는다
    private fun fixedRate(
        scheduler: String,
        method: String,
    ): FixedRateTask =
        scheduledProcessor.scheduledTasks
            .map { it.task }
            .filterIsInstance<FixedRateTask>()
            .single { it.runnable.toString().contains(scheduler) && it.runnable.toString().endsWith(".$method") }
}
