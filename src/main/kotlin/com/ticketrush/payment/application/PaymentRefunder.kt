package com.ticketrush.payment.application

import com.ticketrush.payment.domain.Payment
import com.ticketrush.payment.domain.PaymentConflictException
import com.ticketrush.payment.domain.PaymentGatewayPort
import com.ticketrush.payment.domain.PaymentGatewayResult
import com.ticketrush.payment.domain.PaymentGatewayUnavailableException
import com.ticketrush.payment.domain.RefundIdempotencyKey
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.util.UUID

private val logger = KotlinLogging.logger {}

// 환불 호출 결과를 해석하는 규칙을 한곳에 둔다. 취소 요청과 재시도 스케줄러가 같은 규칙으로 시도 횟수를 센다
@Component
class PaymentRefunder(
    private val paymentGateway: PaymentGatewayPort,
) {
    sealed interface Outcome {
        // PG가 확정한 결과. 거절도 여기에 담기고 시도로 센다
        data class Completed(
            val result: PaymentGatewayResult,
        ) : Outcome

        // PG가 같은 환불을 처리 중이다. 실패가 아니라서 시도로 세지 않는다
        data object InProgress : Outcome

        // PG에 닿지 않았다(서킷 OPEN, 일시 장애, 인증 설정 오류). 시도로 세지 않는다
        data object Unreachable : Outcome
    }

    @Suppress("TooGenericExceptionCaught")
    fun refund(
        payment: Payment,
        reservationKey: UUID,
    ): Outcome {
        val pgTransactionId = requireNotNull(payment.pgTransactionId) { "취소 대상인데 원 거래 id가 없습니다: ${payment.id}" }
        val refundKey = RefundIdempotencyKey.of(reservationKey, payment.refundAttemptCount)
        return try {
            val result = paymentGateway.refund(pgTransactionId, payment.amount, refundKey)
            if (result is PaymentGatewayResult.Declined) {
                logger.warn { "환불이 거절됐습니다: paymentId=${payment.id}, reason=${result.reason}" }
            }
            Outcome.Completed(result)
        } catch (expected: PaymentConflictException) {
            logger.warn { "환불이 PG에서 아직 처리 중입니다. 시도로 세지 않고 다시 확인합니다: paymentId=${payment.id}" }
            Outcome.InProgress
        } catch (expected: PaymentGatewayUnavailableException) {
            logger.warn { "PG에 닿지 않아 환불하지 못했습니다. 시도로 세지 않습니다: paymentId=${payment.id}" }
            Outcome.Unreachable
        } catch (e: Exception) {
            logger.error(e) { "환불 호출 중 예상하지 못한 오류가 났습니다. 거절로 보고 시도로 셉니다: paymentId=${payment.id}" }
            Outcome.Completed(PaymentGatewayResult.Declined(reason = e.message ?: e.javaClass.simpleName))
        }
    }
}
