package com.ticketrush.payment.application

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.ticketrush.payment.domain.Payment
import com.ticketrush.payment.domain.PaymentGatewayPort
import com.ticketrush.payment.domain.PaymentGatewayResult
import com.ticketrush.payment.domain.PaymentStatus
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.Test

// 취소 확정이 이미 커밋된 뒤라서, 환불 결과를 DB에 반영하다 실패해도 사용자 요청은 실패시키지 않는다
class PaymentCancelCommandServiceRefundRecordTest {
    private val cancelRecorder = mockk<PaymentCancelRecorder>()
    private val paymentGateway = mockk<PaymentGatewayPort>()
    private val refundRecorder = mockk<PaymentRefundRecorder>()
    private val service = PaymentCancelCommandService(cancelRecorder, paymentGateway, refundRecorder)
    private val logs = ListAppender<ILoggingEvent>()
    private val serviceLogger = LoggerFactory.getLogger(PaymentCancelCommandService::class.java.name) as Logger

    @BeforeEach
    fun captureLogs() {
        logs.start()
        serviceLogger.addAppender(logs)
        every { cancelRecorder.confirmCancel(RESERVATION_NO, PHONE) } returns CanceledPayment(취소된_결제(), UUID.randomUUID())
    }

    @AfterEach
    fun releaseLogs() {
        serviceLogger.detachAppender(logs)
        logs.stop()
    }

    @Test
    fun `환불은 성공했는데 결과 반영이 실패해도 취소는 유지되고 CANCELED 결제를 돌려준다`() {
        every { paymentGateway.refund("PG-TXN", 200_000, any()) } returns PaymentGatewayResult.Approved("PG-TXN")
        every { refundRecorder.recordCancelSuccess(any(), any()) } throws IllegalStateException("DB 오류")

        val result = service.cancel(RESERVATION_NO, PHONE)

        assertThat(result.payment.status).isEqualTo(PaymentStatus.CANCELED)
        assertThat(logs.list.single { it.level == Level.ERROR }.formattedMessage).contains("환불 결과를 반영하지 못했습니다")
    }

    @Test
    fun `환불 거절을 기록하다 실패해도 취소는 유지되고 CANCELED 결제를 돌려준다`() {
        every { paymentGateway.refund("PG-TXN", 200_000, any()) } returns PaymentGatewayResult.Declined("한도 초과")
        every { refundRecorder.recordFailure(any(), any()) } throws IllegalStateException("DB 오류")

        val result = service.cancel(RESERVATION_NO, PHONE)

        assertThat(result.payment.status).isEqualTo(PaymentStatus.CANCELED)
        assertThat(logs.list.filter { it.level == Level.ERROR }).hasSize(1)
    }

    private fun 취소된_결제(): Payment =
        Payment(id = 1L, reservationId = 10L, amount = 200_000).apply {
            markSuccess("PG-TXN", LocalDateTime.of(2026, 1, 1, 0, 0))
            markCanceled()
        }

    companion object {
        private const val RESERVATION_NO = "RESNO0000001"
        private const val PHONE = "01011112222"
    }
}
