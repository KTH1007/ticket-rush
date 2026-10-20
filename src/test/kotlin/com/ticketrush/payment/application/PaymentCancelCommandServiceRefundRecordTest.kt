package com.ticketrush.payment.application

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.ticketrush.payment.PaymentPolicyProperties
import com.ticketrush.payment.domain.Payment
import com.ticketrush.payment.domain.PaymentGatewayPort
import com.ticketrush.payment.domain.PaymentGatewayResult
import com.ticketrush.payment.domain.PaymentRepositoryPort
import com.ticketrush.payment.domain.PaymentStatus
import com.ticketrush.support.testClientIp
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.Test

// 취소 확정이 이미 커밋된 뒤라서, 환불 결과를 DB에 반영하다 실패해도 사용자 요청은 실패시키지 않는다
class PaymentCancelCommandServiceRefundRecordTest {
    private val cancelRecorder = mockk<PaymentCancelRecorder>()
    private val paymentGateway = mockk<PaymentGatewayPort>()
    private val refundRecorder = mockk<PaymentRefundRecorder>()
    private val paymentRepository = mockk<PaymentRepositoryPort>()
    private val service = PaymentCancelCommandService(cancelRecorder, PaymentRefunder(paymentGateway), refundRecorder, paymentRepository)
    private val logs = ListAppender<ILoggingEvent>()
    private val serviceLogger = LoggerFactory.getLogger(PaymentCancelCommandService::class.java.name) as Logger

    @BeforeEach
    fun captureLogs() {
        logs.start()
        serviceLogger.addAppender(logs)
        every { cancelRecorder.confirmCancel(RESERVATION_NO, PHONE, testClientIp) } returns CanceledPayment(취소된_결제(), UUID.randomUUID())
        every { paymentRepository.findByReservationId(10L) } returns 취소된_결제()
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

        val result = service.cancel(RESERVATION_NO, PHONE, testClientIp)

        assertThat(result.payment.status).isEqualTo(PaymentStatus.CANCELED)
        assertThat(logs.list.single { it.level == Level.ERROR }.formattedMessage).contains("환불 결과를 반영하지 못했습니다")
    }

    @Test
    fun `환불 거절을 기록하다 실패해도 취소는 유지되고 CANCELED 결제를 돌려준다`() {
        every { paymentGateway.refund("PG-TXN", 200_000, any()) } returns PaymentGatewayResult.Declined("한도 초과")
        every { refundRecorder.recordFailure(any(), any()) } throws IllegalStateException("DB 오류")

        val result = service.cancel(RESERVATION_NO, PHONE, testClientIp)

        assertThat(result.payment.status).isEqualTo(PaymentStatus.CANCELED)
        assertThat(logs.list.filter { it.level == Level.ERROR }).hasSize(1)
    }

    // 레코더는 저장 전에 넘겨받은 객체를 먼저 바꾼다. 저장이 실패해도 그 객체를 돌려주면 응답은 REFUNDED인데 DB는 CANCELED가 된다
    @Test
    fun `환불은 승인됐는데 저장이 실패하면 응답은 REFUNDED가 아니라 저장된 CANCELED 결제다`() {
        val service = 실제_레코더로_저장이_실패하는_서비스()
        every { paymentGateway.refund("PG-TXN", 200_000, any()) } returns PaymentGatewayResult.Approved("PG-TXN")

        val result = service.cancel(RESERVATION_NO, PHONE, testClientIp)

        assertThat(result.payment.status).isEqualTo(PaymentStatus.CANCELED)
    }

    @Test
    fun `환불 거절을 기록하다 저장이 실패하면 응답의 시도 횟수는 저장된 값이다`() {
        val service = 실제_레코더로_저장이_실패하는_서비스()
        every { paymentGateway.refund("PG-TXN", 200_000, any()) } returns PaymentGatewayResult.Declined("한도 초과")

        val result = service.cancel(RESERVATION_NO, PHONE, testClientIp)

        assertThat(result.payment.refundAttemptCount).isEqualTo(0)
    }

    private fun 실제_레코더로_저장이_실패하는_서비스(): PaymentCancelCommandService {
        val failingRepository = mockk<PaymentRepositoryPort>()
        every { failingRepository.save(any()) } throws IllegalStateException("DB 오류")
        val policy = PaymentPolicyProperties(staleClaimTimeout = Duration.ofSeconds(10), reclaimInterval = Duration.ofSeconds(5))
        val recorder = PaymentRefundRecorder(failingRepository, mockk<PaymentHistoryRecorder>(relaxed = true), policy)
        return PaymentCancelCommandService(cancelRecorder, PaymentRefunder(paymentGateway), recorder, paymentRepository)
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
