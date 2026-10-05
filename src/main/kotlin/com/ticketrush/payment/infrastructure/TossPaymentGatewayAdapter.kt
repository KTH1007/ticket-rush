package com.ticketrush.payment.infrastructure

import com.ticketrush.payment.TossProperties
import com.ticketrush.payment.domain.PaymentConflictException
import com.ticketrush.payment.domain.PaymentGatewayPort
import com.ticketrush.payment.domain.PaymentGatewayResult
import com.ticketrush.payment.domain.PaymentInquiryResult
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
private const val NOT_FOUND_PAYMENT = "NOT_FOUND_PAYMENT"
private const val ALREADY_PROCESSING_REQUEST = "ALREADY_PROCESSING_REQUEST"
private const val ALREADY_CANCELED_PAYMENT = "ALREADY_CANCELED_PAYMENT"
private val ALREADY_PROCESSED_CODES = setOf("ALREADY_PROCESSED_PAYMENT", "DUPLICATED_REQUEST")

// application.yml의 jdbc.time_zone과 같은 존. LocalDateTime이 이 존 기준으로 저장된다
private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

// 실제 Toss Payments confirm/cancel/조회 API를 호출하는 구현체. API별 응답 변환이 한 클래스에 모여 함수가 많다
@Suppress("TooManyFunctions")
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
            declinedOrRethrow(e, paymentKey, orderId, amount)
        }

    // 4xx만 거절로 변환한다. 5xx/타임아웃은 그대로 던져서 인프라 장애로 구분되게 한다.
    private fun declinedOrRethrow(
        e: HttpClientErrorException,
        paymentKey: String,
        orderId: String,
        amount: Int,
    ): PaymentGatewayResult {
        val code = errorBodyFrom(e)?.code
        // 첫 요청이 아직 처리 중이라 나중에 승인될 수 있으므로 거절로 확정하지 않는다
        if (e.statusCode == HttpStatus.CONFLICT && code == IDEMPOTENT_REQUEST_PROCESSING) {
            throw PaymentConflictException(e)
        }
        if (e.statusCode == HttpStatus.BAD_REQUEST && code == ALREADY_PROCESSING_REQUEST) {
            throw PaymentConflictException(e)
        }
        throwIfUnauthorized(e)
        if (e.statusCode == HttpStatus.BAD_REQUEST && code in ALREADY_PROCESSED_CODES) {
            return resolveByInquiry(paymentKey, orderId, amount)
        }
        return PaymentGatewayResult.Declined(reason = declinedReasonFrom(e))
    }

    // 이미 승인됐을 수 있어 거절로 확정하지 않고 Toss에 직접 물어 판정한다.
    // 같은 빈 안의 호출이라 프록시를 타지 않으므로 서킷은 charge 한 번으로만 집계된다
    private fun resolveByInquiry(
        paymentKey: String,
        orderId: String,
        amount: Int,
    ): PaymentGatewayResult {
        val payment = fetchPayment(paymentKey)
        return when {
            payment == null -> PaymentGatewayResult.Declined(reason = "Toss에 해당 결제가 없습니다")
            payment.status != "DONE" -> PaymentGatewayResult.Declined(reason = "Toss status: ${payment.status}")
            // 다른 주문에서 승인된 paymentKey를 가져다 쓴 요청이 승인으로 둔갑하지 않게 한다. 필드가 없으면 불일치로 본다
            payment.orderId != orderId || payment.totalAmount != amount -> PaymentGatewayResult.Declined(reason = "다른 주문의 결제")
            else -> PaymentGatewayResult.Approved(payment.paymentKey, parseApprovedAt(payment.approvedAt))
        }
    }

    // 시크릿 키 설정 실수가 거절이나 미승인으로 확정돼 결제가 잘못 닫히는 걸 막으려고 그대로 던진다
    private fun throwIfUnauthorized(e: HttpClientErrorException) {
        if (e.statusCode != HttpStatus.UNAUTHORIZED) return
        logger.error { "Toss 인증 실패(401), 시크릿 키 설정 확인 필요" }
        throw e
    }

    @CircuitBreaker(name = "paymentGateway")
    override fun inquire(paymentKey: String): PaymentInquiryResult =
        fetchPayment(paymentKey)?.let(::toInquiryResult) ?: PaymentInquiryResult.NotFound

    // 없는 결제만 null이다. 그 밖의 4xx는 승인 여부를 모르므로 미승인으로 단정하지 않고 던진다
    private fun fetchPayment(paymentKey: String): TossPaymentResponse? =
        try {
            val response =
                restClient
                    .get()
                    .uri("${toss.baseUrl}/v1/payments/{paymentKey}", paymentKey)
                    .header("Authorization", basicAuth())
                    .retrieve()
                    .body(TossPaymentResponse::class.java)
            requireNotNull(response) { "토스 조회 응답 본문이 비어 있습니다" }
        } catch (e: HttpClientErrorException) {
            if (e.statusCode == HttpStatus.NOT_FOUND && errorBodyFrom(e)?.code == NOT_FOUND_PAYMENT) {
                null
            } else {
                throwIfUnauthorized(e)
                throw e
            }
        }

    @CircuitBreaker(name = "paymentGateway")
    override fun refund(
        pgTransactionId: String,
        amount: Int,
        idempotencyKey: UUID,
    ): PaymentGatewayResult =
        try {
            val response =
                restClient
                    .post()
                    .uri("${toss.baseUrl}/v1/payments/$pgTransactionId/cancel")
                    .header("Authorization", basicAuth())
                    .header("Idempotency-Key", idempotencyKey.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    // cancelAmount를 생략하면 Toss가 전액 취소하므로, 의도한 금액을 명시한다
                    .body(mapOf("cancelReason" to "사용자 취소 요청", "cancelAmount" to amount))
                    .retrieve()
                    .body(TossPaymentResponse::class.java)
            toCancelResult(requireNotNull(response) { "토스 cancel 응답 본문이 비어 있습니다" })
        } catch (e: HttpClientErrorException) {
            refundDeclinedOrRethrow(e, pgTransactionId)
        }

    // 환불 4xx 분류. 첫 요청이 처리 중이면 거절로 확정하지 않고, 이미 취소됐다는 응답은 조회로 실제 상태를 확인한다
    private fun refundDeclinedOrRethrow(
        e: HttpClientErrorException,
        pgTransactionId: String,
    ): PaymentGatewayResult {
        val code = errorBodyFrom(e)?.code
        if (e.statusCode == HttpStatus.CONFLICT && code == IDEMPOTENT_REQUEST_PROCESSING) {
            throw PaymentConflictException(e)
        }
        throwIfUnauthorized(e)
        if (e.statusCode == HttpStatus.BAD_REQUEST && code == ALREADY_CANCELED_PAYMENT) {
            return resolveRefundByInquiry(pgTransactionId)
        }
        return PaymentGatewayResult.Declined(reason = declinedReasonFrom(e))
    }

    // 이전 시도나 외부(운영자)의 전액 취소를 우리가 모르는 경우라, 조회가 CANCELED일 때만 환불 완료로 본다
    private fun resolveRefundByInquiry(pgTransactionId: String): PaymentGatewayResult {
        val payment = fetchPayment(pgTransactionId)
        return when {
            payment == null -> PaymentGatewayResult.Declined(reason = "Toss에 해당 결제가 없습니다")
            payment.status != "CANCELED" -> PaymentGatewayResult.Declined(reason = "Toss status: ${payment.status}")
            else -> PaymentGatewayResult.Approved(pgTransactionId = pgTransactionId)
        }
    }

    private fun basicAuth(): String = "Basic " + Base64.getEncoder().encodeToString("${toss.secretKey}:".toByteArray())

    private fun toResult(response: TossPaymentResponse): PaymentGatewayResult =
        if (response.status == "DONE") {
            PaymentGatewayResult.Approved(pgTransactionId = response.paymentKey, approvedAt = parseApprovedAt(response.approvedAt))
        } else {
            PaymentGatewayResult.Declined(reason = "예상치 못한 status: ${response.status}")
        }

    private fun toInquiryResult(response: TossPaymentResponse): PaymentInquiryResult =
        if (response.status == "DONE") {
            PaymentInquiryResult.Done(pgTransactionId = response.paymentKey, approvedAt = parseApprovedAt(response.approvedAt))
        } else {
            PaymentInquiryResult.NotApproved(status = response.status)
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

    // 항상 전액을 요청하므로 부분 취소(PARTIAL_CANCELED)는 환불 완료가 아니다. 성공은 DONE이 아니라 CANCELED로 온다
    private fun toCancelResult(response: TossPaymentResponse): PaymentGatewayResult =
        if (response.status == "CANCELED") {
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
        val orderId: String? = null,
        val totalAmount: Int? = null,
    )

    private data class TossErrorResponse(
        val code: String,
        val message: String,
    )
}
