package com.ticketrush.payment.infrastructure

import com.ticketrush.payment.TossProperties
import com.ticketrush.payment.domain.PaymentConflictException
import com.ticketrush.payment.domain.PaymentGatewayPort
import com.ticketrush.payment.domain.PaymentGatewayResult
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.UUID

private val logger = KotlinLogging.logger {}

private const val IDEMPOTENT_REQUEST_PROCESSING = "IDEMPOTENT_REQUEST_PROCESSING"

// application.yml의 jdbc.time_zone과 같은 존. LocalDateTime이 이 존 기준으로 저장된다
private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

// 실제 Toss Payments confirm/cancel API를 호출하는 구현체
@Profile("toss")
@Component
class TossPaymentGatewayAdapter(
    private val restClient: RestClient,
    private val toss: TossProperties,
) : PaymentGatewayPort {
    @CircuitBreaker(name = "paymentGateway")
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
        } catch (e: HttpClientErrorException) {
            // 첫 요청이 아직 처리 중이라 나중에 승인될 수 있으므로 거절로 확정하지 않는다
            if (e.statusCode == HttpStatus.CONFLICT && errorBodyFrom(e)?.code == IDEMPOTENT_REQUEST_PROCESSING) {
                throw PaymentConflictException(e)
            }
            // 4xx만 거절로 변환한다. 5xx/타임아웃은 그대로 던져서 인프라 장애로 구분되게 한다.
            PaymentGatewayResult.Declined(reason = declinedReasonFrom(e))
        }

    @CircuitBreaker(name = "paymentGateway")
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
            toCancelResult(requireNotNull(response) { "토스 cancel 응답 본문이 비어 있습니다" })
        } catch (e: HttpClientErrorException) {
            PaymentGatewayResult.Declined(reason = declinedReasonFrom(e))
        }

    private fun basicAuth(): String = "Basic " + Base64.getEncoder().encodeToString("${toss.secretKey}:".toByteArray())

    private fun toResult(response: TossPaymentResponse): PaymentGatewayResult =
        if (response.status == "DONE") {
            PaymentGatewayResult.Approved(pgTransactionId = response.paymentKey, approvedAt = parseApprovedAt(response.approvedAt))
        } else {
            PaymentGatewayResult.Declined(reason = "예상치 못한 status: ${response.status}")
        }

    // 이미 승인된 뒤라 파싱 실패로 예외를 던지면 클레임이 PENDING으로 남는다. null로 두고 호출 측이 서버 시각으로 대체한다
    private fun parseApprovedAt(raw: String?): LocalDateTime? {
        if (raw == null) return null
        return try {
            OffsetDateTime.parse(raw).atZoneSameInstant(SEOUL).toLocalDateTime()
        } catch (e: DateTimeParseException) {
            logger.warn(e) { "토스 approvedAt 파싱에 실패해 서버 시각으로 대체합니다: approvedAt=$raw" }
            null
        }
    }

    // 취소 성공은 DONE이 아니라 CANCELED/PARTIAL_CANCELED로 온다
    private fun toCancelResult(response: TossPaymentResponse): PaymentGatewayResult =
        if (response.status == "CANCELED" || response.status == "PARTIAL_CANCELED") {
            PaymentGatewayResult.Approved(pgTransactionId = response.paymentKey)
        } else {
            PaymentGatewayResult.Declined(reason = "예상치 못한 status: ${response.status}")
        }

    private fun errorBodyFrom(e: HttpClientErrorException): TossErrorResponse? =
        runCatching { e.getResponseBodyAs(TossErrorResponse::class.java) }.getOrNull()

    private fun declinedReasonFrom(e: HttpClientErrorException): String {
        val error = errorBodyFrom(e)
        return if (error != null) "${error.code}: ${error.message}" else e.message.orEmpty()
    }

    private data class TossPaymentResponse(
        val paymentKey: String,
        val status: String,
        val approvedAt: String? = null,
    )

    private data class TossErrorResponse(
        val code: String,
        val message: String,
    )
}
