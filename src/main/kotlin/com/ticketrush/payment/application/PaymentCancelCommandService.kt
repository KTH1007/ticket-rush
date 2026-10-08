package com.ticketrush.payment.application

import com.ticketrush.payment.domain.Payment
import com.ticketrush.payment.domain.PaymentCancelResult
import com.ticketrush.payment.domain.PaymentConflictException
import com.ticketrush.payment.domain.PaymentGatewayPort
import com.ticketrush.payment.domain.PaymentGatewayResult
import com.ticketrush.payment.domain.RefundIdempotencyKey
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

// @Transactional이 아니다. 취소 확정이 먼저 커밋돼야, 환불 성공 뒤 커밋이 실패해 롤백되면서
// 돈은 돌아갔는데 예약이 PAID로 남는 일이 없다. 환불을 못 끝내면 CANCELED로 남아 재시도 스케줄러가 이어받는다.
@Service
class PaymentCancelCommandService(
    private val cancelRecorder: PaymentCancelRecorder,
    private val paymentGateway: PaymentGatewayPort,
    private val refundRecorder: PaymentRefundRecorder,
) {
    fun cancel(
        reservationNo: String,
        phone: String,
    ): PaymentCancelResult {
        val canceled = cancelRecorder.confirmCancel(reservationNo, phone)
        return PaymentCancelResult(applyRefund(canceled), reservationNo)
    }

    private fun applyRefund(canceled: CanceledPayment): Payment {
        val result = requestRefund(canceled) ?: return canceled.payment
        return recordRefund(canceled.payment, result)
    }

    // 환불 호출이 예외를 던져도(네트워크 오류 등) 확정된 취소는 그대로다. "환불 실패해도 취소는 확정된다"는
    // 계약을 예외 경로에서도 지키려고 거절로 바꿔 돌려준다. PG가 같은 환불을 처리 중인 충돌은 실패가 아니라서 null로 돌려 시도로 세지 않는다.
    @Suppress("TooGenericExceptionCaught")
    private fun requestRefund(canceled: CanceledPayment): PaymentGatewayResult? {
        val payment = canceled.payment
        val pgTransactionId = requireNotNull(payment.pgTransactionId) { "취소 대상인데 원 거래 id가 없습니다: ${payment.id}" }
        val refundKey = RefundIdempotencyKey.of(canceled.reservationKey, payment.refundAttemptCount)
        return try {
            val result = paymentGateway.refund(pgTransactionId, payment.amount, refundKey)
            if (result is PaymentGatewayResult.Declined) {
                logger.warn { "환불이 거절됐습니다. 취소는 유지하고 결제는 CANCELED로 남깁니다: paymentId=${payment.id}, reason=${result.reason}" }
            }
            result
        } catch (expected: PaymentConflictException) {
            logger.warn { "환불이 PG에서 아직 처리 중입니다. 결제는 CANCELED로 남깁니다: paymentId=${payment.id}" }
            null
        } catch (e: Exception) {
            logger.error(e) { "환불 요청 중 오류가 발생했습니다. 취소는 유지하고 결제는 CANCELED로 남깁니다: paymentId=${payment.id}" }
            PaymentGatewayResult.Declined(reason = e.message ?: e.javaClass.simpleName)
        }
    }

    // 취소는 이미 커밋돼 환불 결과를 못 적어도 요청을 실패시키지 않는다. 결제가 CANCELED로 남아 재시도 스케줄러가 같은 키로 다시 요청한다
    @Suppress("TooGenericExceptionCaught")
    private fun recordRefund(
        payment: Payment,
        result: PaymentGatewayResult,
    ): Payment =
        try {
            when (result) {
                is PaymentGatewayResult.Approved -> refundRecorder.recordCancelSuccess(payment, result.pgTransactionId)
                is PaymentGatewayResult.Declined -> refundRecorder.recordFailure(payment, result.reason)
            }
        } catch (e: Exception) {
            logger.error(e) { "환불 결과를 반영하지 못했습니다. 결제는 CANCELED로 남고 재시도 스케줄러가 이어받습니다: paymentId=${payment.id}" }
            payment
        }
}
