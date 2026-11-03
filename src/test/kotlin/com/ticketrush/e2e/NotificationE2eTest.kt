package com.ticketrush.e2e

import org.assertj.core.api.Assertions.assertThat
import java.time.Duration
import kotlin.test.Test

// E29: 결제 확정과 취소가 outbox를 거쳐 알림 처리까지 간다
class NotificationE2eTest : E2eTest() {
    @Test
    fun `E29 결제 확정과 취소는 outbox에 기록되고 릴레이가 처리 완료로 바꾼다`() {
        val purchase = purchase(seed.openEvent())

        val paidDone = await(Duration.ofSeconds(WAIT_SECONDS)) { outboxStatus(purchase.reservationId, "RESERVATION_PAID") == "DONE" }
        assertThat(paidDone).`as`("확정 알림 상태: ${outboxStatus(purchase.reservationId, "RESERVATION_PAID")}").isTrue()

        val canceled = api.cancel(purchase.reservationNo, purchase.phone, nextIp())
        assertThat(canceled.status).`as`(canceled.body).isEqualTo(HTTP_OK)
        val canceledDone =
            await(Duration.ofSeconds(WAIT_SECONDS)) { outboxStatus(purchase.reservationId, "RESERVATION_CANCELED") == "DONE" }
        assertThat(canceledDone).`as`("취소 알림 상태: ${outboxStatus(purchase.reservationId, "RESERVATION_CANCELED")}").isTrue()
    }

    private fun outboxStatus(
        reservationId: Long,
        eventType: String,
    ): String? =
        jdbc
            .queryForList(
                "SELECT status FROM outbox WHERE aggregate_id = ? AND event_type = ?",
                String::class.java,
                reservationId,
                eventType,
            ).firstOrNull()

    private companion object {
        const val WAIT_SECONDS = 10L
    }
}
