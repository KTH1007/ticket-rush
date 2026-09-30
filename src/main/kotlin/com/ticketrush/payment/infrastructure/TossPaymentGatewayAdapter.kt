package com.ticketrush.payment.infrastructure

import com.ticketrush.payment.TossProperties
import com.ticketrush.payment.domain.PaymentGatewayPort
import com.ticketrush.payment.domain.PaymentGatewayResult
import org.springframework.context.annotation.Profile
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpStatusCodeException
import org.springframework.web.client.RestClient
import java.util.Base64
import java.util.UUID

// 실제 Toss Payments confirm/cancel API를 호출하는 구현체
@Profile("toss")
@Component
class TossPaymentGatewayAdapter(
    private val restClient: RestClient,
    private val toss: TossProperties,
) : PaymentGatewayPort {
    override fun charge(
        paymentKey: String,
        orderId: String,
        amount: Int,
        idempotencyKey: UUID,
    ): PaymentGatewayResult =
        try {
            val response =
                restClient
                    .post()
                    .uri("${toss.baseUrl}/v1/payments/confirm")
                    .header("Authorization", basicAuth())
                    .header("Idempotency-Key", idempotencyKey.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mapOf("paymentKey" to paymentKey, "orderId" to orderId, "amount" to amount))
                    .retrieve()
                    .body(TossPaymentResponse::class.java)
            toResult(requireNotNull(response) { "토스 confirm 응답 본문이 비어 있습니다" })
        } catch (e: HttpStatusCodeException) {
            PaymentGatewayResult.Declined(reason = declinedReasonFrom(e))
        }

    override fun refund(
        pgTransactionId: String,
        amount: Int,
    ): PaymentGatewayResult =
        try {
            val response =
                restClient
                    .post()
                    .uri("${toss.baseUrl}/v1/payments/$pgTransactionId/cancel")
                    .header("Authorization", basicAuth())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mapOf("cancelReason" to "사용자 취소 요청"))
                    .retrieve()
                    .body(TossPaymentResponse::class.java)
            toResult(requireNotNull(response) { "토스 cancel 응답 본문이 비어 있습니다" })
        } catch (e: HttpStatusCodeException) {
            PaymentGatewayResult.Declined(reason = declinedReasonFrom(e))
        }

    private fun basicAuth(): String = "Basic " + Base64.getEncoder().encodeToString("${toss.secretKey}:".toByteArray())

    private fun toResult(response: TossPaymentResponse): PaymentGatewayResult =
        if (response.status == "DONE") {
            PaymentGatewayResult.Approved(pgTransactionId = response.paymentKey)
        } else {
            PaymentGatewayResult.Declined(reason = "예상치 못한 status: ${response.status}")
        }

    private fun declinedReasonFrom(e: HttpStatusCodeException): String {
        val error = runCatching { e.getResponseBodyAs(TossErrorResponse::class.java) }.getOrNull()
        return if (error != null) "${error.code}: ${error.message}" else e.message.orEmpty()
    }

    private data class TossPaymentResponse(
        val paymentKey: String,
        val status: String,
    )

    private data class TossErrorResponse(
        val code: String,
        val message: String,
    )
}
