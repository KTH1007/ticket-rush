package com.ticketrush.payment.application

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.ticketrush.payment.PaymentPolicyProperties
import com.ticketrush.payment.domain.Payment
import com.ticketrush.payment.domain.PaymentRepositoryPort
import com.ticketrush.payment.domain.PaymentStatus
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.slf4j.LoggerFactory
import java.time.Duration
import kotlin.test.Test

class PaymentRefundRecorderTest {
    private val paymentRepository = mockk<PaymentRepositoryPort>()
    private val historyRecorder = mockk<PaymentHistoryRecorder>()
    private val logs = ListAppender<ILoggingEvent>()
    private val recorderLogger = LoggerFactory.getLogger(PaymentRefundRecorder::class.java.name) as Logger

    @BeforeEach
    fun captureLogs() {
        every { paymentRepository.save(any()) } answers { firstArg() }
        justRun { historyRecorder.record(any(), any(), any(), any()) }
        logs.start()
        recorderLogger.addAppender(logs)
    }

    @AfterEach
    fun releaseLogs() {
        recorderLogger.detachAppender(logs)
        logs.stop()
    }

    @Test
    fun `환불 실패를 기록하면 이력에 현재 횟수와 한도와 사유가 남고 한도 미만이면 알리지 않는다`() {
        val recorder = recorder(maxAttempts = 5)

        recorder.recordFailure(취소된_결제(), "한도 초과")

        verify(exactly = 1) {
            historyRecorder.record(PAYMENT_ID, PaymentStatus.CANCELED, PaymentStatus.CANCELED, "환불 실패 1/5: 한도 초과")
        }
        assertThat(logs.list.filter { it.level == Level.ERROR }).isEmpty()
    }

    @Test
    fun `시도 횟수가 한도에 도달하면 ERROR로 수동 처리가 필요하다고 알린다`() {
        val recorder = recorder(maxAttempts = 1)

        recorder.recordFailure(취소된_결제(), "거절")

        val alert = logs.list.single { it.level == Level.ERROR }.formattedMessage
        assertThat(alert).contains("수동 처리 필요").contains("paymentId=$PAYMENT_ID").contains("reservationId=$RESERVATION_ID")
    }

    private fun recorder(maxAttempts: Int): PaymentRefundRecorder =
        PaymentRefundRecorder(
            paymentRepository,
            historyRecorder,
            PaymentPolicyProperties(
                staleClaimTimeout = Duration.ofSeconds(10),
                reclaimInterval = Duration.ofSeconds(5),
                refundMaxAttempts = maxAttempts,
            ),
        )

    private fun 취소된_결제(): Payment =
        Payment(
            id = PAYMENT_ID,
            reservationId = RESERVATION_ID,
            amount = 100_000,
            status = PaymentStatus.CANCELED,
            pgTransactionId = "pk-recorder",
        )

    companion object {
        private const val PAYMENT_ID = 1L
        private const val RESERVATION_ID = 10L
    }
}
