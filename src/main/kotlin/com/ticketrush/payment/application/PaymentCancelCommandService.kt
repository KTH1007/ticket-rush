package com.ticketrush.payment.application

import com.ticketrush.payment.domain.Payment
import com.ticketrush.payment.domain.PaymentCancelResult
import com.ticketrush.payment.domain.PaymentGatewayResult
import com.ticketrush.payment.domain.PaymentRepositoryPort
import com.ticketrush.shared.ClientIp
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

// @Transactional이 아니다. 취소 확정이 먼저 커밋돼야, 환불 성공 뒤 커밋이 실패해 롤백되면서
// 돈은 돌아갔는데 예약이 PAID로 남는 일이 없다. 환불을 못 끝내면 CANCELED로 남아 재시도 스케줄러가 이어받는다.
@Service
class PaymentCancelCommandService(
    private val cancelRecorder: PaymentCancelRecorder,
    private val refunder: PaymentRefunder,
    private val refundRecorder: PaymentRefundRecorder,
    private val paymentRepository: PaymentRepositoryPort,
) {
    fun cancel(
        reservationNo: String,
        phone: String,
        clientIp: ClientIp,
    ): PaymentCancelResult {
        val canceled = cancelRecorder.confirmCancel(reservationNo, phone, clientIp)
        return PaymentCancelResult(applyRefund(canceled), reservationNo)
    }

    // 환불 호출이 어떻게 끝나도 확정된 취소는 그대로다. 결과가 없으면(처리 중, PG에 안 닿음) 시도로 세지 않고 CANCELED로 남긴다
    private fun applyRefund(canceled: CanceledPayment): Payment {
        val outcome = refunder.refund(canceled.payment, canceled.reservationKey)
        val result = (outcome as? PaymentRefunder.Outcome.Completed)?.result ?: return canceled.payment
        return recordRefund(canceled.payment, result)
    }

    // 취소는 이미 커밋돼 환불 결과를 못 적어도 저장된 결제를 다시 읽어 응답한다. 재조회까지 실패하면 예외가 나가지만
    // 결제는 CANCELED로 남아 재시도 스케줄러가 같은 키로 이어받는다
    @Suppress("TooGenericExceptionCaught")
    private fun recordRefund(
        payment: Payment,
        result: PaymentGatewayResult,
    ): Payment =
        try {
            when (result) {
                is PaymentGatewayResult.Approved -> refundRecorder.recordCancelSuccess(payment, result)
                is PaymentGatewayResult.Declined -> refundRecorder.recordFailure(payment, result.reason)
            }
        } catch (e: Exception) {
            logger.error(e) { "환불 결과를 반영하지 못했습니다. 결제는 CANCELED로 남고 재시도 스케줄러가 이어받습니다: paymentId=${payment.id}, result=$result" }
            // 반영 도중에 넘겨받은 객체를 먼저 바꿨을 수 있어, 응답은 저장된 값을 다시 읽어 만든다
            paymentRepository.findByReservationId(payment.reservationId) ?: payment
        }
}
