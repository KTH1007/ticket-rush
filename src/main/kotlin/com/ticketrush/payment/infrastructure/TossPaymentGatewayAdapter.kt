package com.ticketrush.payment.infrastructure

import com.ticketrush.payment.domain.PaymentConflictException
import com.ticketrush.payment.domain.PaymentGatewayPort
import com.ticketrush.payment.domain.PaymentGatewayResult
import com.ticketrush.payment.domain.PaymentGatewayUnavailableException
import com.ticketrush.payment.domain.PaymentInquiryResult
import com.ticketrush.shared.AppTimeZone
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

private val logger = KotlinLogging.logger {}

private const val IDEMPOTENT_REQUEST_PROCESSING = "IDEMPOTENT_REQUEST_PROCESSING"
private const val NOT_FOUND_PAYMENT = "NOT_FOUND_PAYMENT"
private const val ALREADY_PROCESSING_REQUEST = "ALREADY_PROCESSING_REQUEST"
private const val ALREADY_CANCELED_PAYMENT = "ALREADY_CANCELED_PAYMENT"
private const val ALREADY_REFUND_PAYMENT = "ALREADY_REFUND_PAYMENT"
private const val ALREADY_REFUNDING_PAYMENT = "ALREADY_REFUNDING_PAYMENT"
private const val NOT_CANCELABLE_AMOUNT = "NOT_CANCELABLE_AMOUNT"
private const val PARTIAL_CANCELED = "PARTIAL_CANCELED"

// 환불 요청에 싣는 사유. 조회 응답의 취소 내역에서 우리 요청과 운영자의 직접 취소를 가르는 기준이기도 하다
private const val REFUND_CANCEL_REASON = "사용자 취소 요청"
private val ALREADY_PROCESSED_CODES = setOf("ALREADY_PROCESSED_PAYMENT", "DUPLICATED_REQUEST")
private val ALREADY_CANCELED_CODES = setOf(ALREADY_CANCELED_PAYMENT, ALREADY_REFUND_PAYMENT)
private val REFUND_IN_PROGRESS_CODES = setOf(ALREADY_REFUNDING_PAYMENT, ALREADY_PROCESSING_REQUEST)

// 실제 Toss Payments confirm/cancel/조회 API를 호출하는 구현체. API별 응답 변환이 한 클래스에 모여 함수가 많다
@Suppress("TooManyFunctions")
@ConditionalOnPaymentGateway(PaymentGatewayType.TOSS)
@Component
class TossPaymentGatewayAdapter(
    private val restClient: RestClient,
    private val toss: TossProperties,
) : PaymentGatewayPort {
    init {
        require(toss.secretKey.isNotBlank()) { "gateway가 toss이면 TOSS_SECRET_KEY가 필요합니다" }
    }

    @CircuitBreaker(name = "paymentGateway", fallbackMethod = "chargeBlocked")
    override fun charge(
        paymentKey: String,
        orderId: String,
        amount: Int,
        idempotencyKey: UUID,
    ): PaymentGatewayResult = unavailableOnInfraFailure { requestCharge(paymentKey, orderId, amount, idempotencyKey) }

    private fun requestCharge(
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
        throwIfTransient(e)
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
            else -> PaymentGatewayResult.Approved(payment.paymentKey, parseApprovedAt(payment.approvedAt, payment.paymentKey))
        }
    }

    // 승인 전용. 429(호출 제한)와 408(시간 초과)을 거절로 확정하면 결제가 FAILED가 되고 홀드가 줄어 사용자가 억울하게 거절당하므로 결과를 확정하지 않는다.
    // 환불에는 쓰지 않는다: 시도 횟수가 안 올라 재시도 스케줄러가 매 틱 같은 키로 Toss를 다시 부르게 되므로 일반 실패로 센다
    private fun throwIfTransient(e: HttpClientErrorException) {
        if (e.statusCode == HttpStatus.TOO_MANY_REQUESTS || e.statusCode == HttpStatus.REQUEST_TIMEOUT) throw PaymentConflictException(e)
    }

    // 조회의 429(호출 제한)와 408(시간 초과)은 호출을 줄이라는 신호라 서킷이 실패로 세어 호출을 멈추게 한다. 승인과 달리 확정할 결과가 없다
    private fun throwIfRateLimited(e: HttpClientErrorException) {
        if (e.statusCode == HttpStatus.TOO_MANY_REQUESTS || e.statusCode == HttpStatus.REQUEST_TIMEOUT) {
            throw PaymentGatewayUnavailableException(cause = e)
        }
    }

    // 시크릿 키 설정 실수가 거절이나 미승인으로 확정돼 결제가 잘못 닫히는 걸 막으려고 그대로 던진다
    private fun throwIfUnauthorized(e: HttpClientErrorException) {
        if (e.statusCode != HttpStatus.UNAUTHORIZED) return
        logger.error { "Toss 인증 실패(401), 시크릿 키 설정 확인 필요" }
        throw e
    }

    @CircuitBreaker(name = "paymentGateway", fallbackMethod = "inquireBlocked")
    override fun inquire(paymentKey: String): PaymentInquiryResult =
        unavailableOnInfraFailure { fetchPayment(paymentKey)?.let(::toInquiryResult) ?: PaymentInquiryResult.NotFound }

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
                throwIfRateLimited(e)
                throw e
            }
        }

    @CircuitBreaker(name = "paymentGateway", fallbackMethod = "refundBlocked")
    override fun refund(
        pgTransactionId: String,
        amount: Int,
        idempotencyKey: UUID,
    ): PaymentGatewayResult =
        try {
            val response =
                restClient
                    .post()
                    .uri("${toss.baseUrl}/v1/payments/{paymentKey}/cancel", pgTransactionId)
                    .header("Authorization", basicAuth())
                    .header("Idempotency-Key", idempotencyKey.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    // cancelAmount를 생략하면 Toss가 전액 취소하므로, 의도한 금액을 명시한다
                    .body(mapOf("cancelReason" to REFUND_CANCEL_REASON, "cancelAmount" to amount))
                    .retrieve()
                    .body(TossPaymentResponse::class.java)
            toCancelResult(requireNotNull(response) { "토스 cancel 응답 본문이 비어 있습니다" })
        } catch (e: HttpClientErrorException) {
            refundDeclinedOrRethrow(e, pgTransactionId)
        }

    // 환불 4xx 분류. 처리 중이면 거절로 확정하지 않고, 이미 취소(환불)됐다는 응답은 조회로 실제 상태를 확인한다
    private fun refundDeclinedOrRethrow(
        e: HttpClientErrorException,
        pgTransactionId: String,
    ): PaymentGatewayResult {
        val code = errorBodyFrom(e)?.code
        if (isRefundInProgress(e, code)) throw PaymentConflictException(e)
        throwIfUnauthorized(e)
        val alreadyCanceled = e.statusCode == HttpStatus.BAD_REQUEST && code in ALREADY_CANCELED_CODES
        val noCancelableAmount = e.statusCode == HttpStatus.FORBIDDEN && code == NOT_CANCELABLE_AMOUNT
        if (alreadyCanceled || noCancelableAmount) {
            return resolveRefundByInquiry(pgTransactionId)
        }
        return PaymentGatewayResult.Declined(reason = declinedReasonFrom(e))
    }

    // 첫 요청이 처리 중이거나 이미 환불 중이면 곧 결과가 나올 수 있어 거절로 확정하지 않는다
    private fun isRefundInProgress(
        e: HttpClientErrorException,
        code: String?,
    ): Boolean =
        (e.statusCode == HttpStatus.CONFLICT && code == IDEMPOTENT_REQUEST_PROCESSING) ||
            (e.statusCode == HttpStatus.BAD_REQUEST && code in REFUND_IN_PROGRESS_CODES)

    // 이전 시도나 외부(운영자)의 취소를 우리가 모르는 경우라, 조회가 CANCELED이거나 부분 취소로 잔액이 0일 때만 환불 완료로 본다
    private fun resolveRefundByInquiry(pgTransactionId: String): PaymentGatewayResult {
        val payment = fetchPayment(pgTransactionId)
        return when {
            payment == null -> PaymentGatewayResult.Declined(reason = "Toss에 해당 결제가 없습니다")
            payment.status == "CANCELED" || (payment.status == "PARTIAL_CANCELED" && payment.balanceAmount == 0) ->
                PaymentGatewayResult.Approved(pgTransactionId = pgTransactionId, externallyCanceled = isExternallyCanceled(payment))
            else -> PaymentGatewayResult.Declined(reason = "Toss status: ${payment.status}")
        }
    }

    // 우리가 보낸 사유의 취소가 하나도 없으면 운영자가 Toss에서 직접 취소한 것이다. 취소 내역이 없으면 판단하지 못하므로 외부로 단정하지 않는다
    private fun isExternallyCanceled(payment: TossPaymentResponse): Boolean {
        val cancels = payment.cancels.orEmpty()
        return cancels.isNotEmpty() && cancels.none { it.cancelReason == REFUND_CANCEL_REASON }
    }

    // 서킷이 열려 Toss를 부르지 않았다. 호출이 나가 결과를 모르는 5xx와 달리 호출 측이 환불 횟수에 세지 않는다
    @Suppress("UnusedPrivateFunction", "UnusedParameter")
    private fun chargeBlocked(
        paymentKey: String,
        orderId: String,
        amount: Int,
        idempotencyKey: UUID,
        e: CallNotPermittedException,
    ): PaymentGatewayResult = throw PaymentGatewayUnavailableException(cause = e)

    @Suppress("UnusedPrivateFunction", "UnusedParameter")
    private fun inquireBlocked(
        paymentKey: String,
        e: CallNotPermittedException,
    ): PaymentInquiryResult = throw PaymentGatewayUnavailableException(cause = e)

    @Suppress("UnusedPrivateFunction", "UnusedParameter")
    private fun refundBlocked(
        pgTransactionId: String,
        amount: Int,
        idempotencyKey: UUID,
        e: CallNotPermittedException,
    ): PaymentGatewayResult = throw PaymentGatewayUnavailableException(cause = e)

    // 승인과 조회의 5xx, 연결 실패, 타임아웃, 응답을 읽다 끊기거나 해석하지 못한 경우는 Toss의 일시 장애라 결과를 확정하지 않는다.
    // 환불에는 쓰지 않는다(호출이 나가 결과를 모르므로 실패로 센다)
    private inline fun <T> unavailableOnInfraFailure(block: () -> T): T =
        try {
            block()
        } catch (e: RestClientException) {
            throw unavailableOrSelf(e)
        }

    // 4xx 같은 상태 오류는 호출 측이 결과를 판단하므로 그대로 두고, 5xx와 그 밖의 통신 오류만 일시 장애로 바꾼다
    private fun unavailableOrSelf(e: RestClientException): RuntimeException =
        if (e is RestClientResponseException && e !is HttpServerErrorException) {
            e
        } else {
            PaymentGatewayUnavailableException(cause = e, serverResponded = e is HttpServerErrorException)
        }

    private fun basicAuth(): String = "Basic " + Base64.getEncoder().encodeToString("${toss.secretKey}:".toByteArray())

    private fun toResult(response: TossPaymentResponse): PaymentGatewayResult =
        if (response.status == "DONE") {
            PaymentGatewayResult.Approved(
                pgTransactionId = response.paymentKey,
                approvedAt = parseApprovedAt(response.approvedAt, response.paymentKey),
            )
        } else {
            PaymentGatewayResult.Declined(reason = "예상치 못한 status: ${response.status}")
        }

    private fun toInquiryResult(response: TossPaymentResponse): PaymentInquiryResult =
        when (response.status) {
            "DONE" ->
                PaymentInquiryResult.Done(
                    pgTransactionId = response.paymentKey,
                    approvedAt = parseApprovedAt(response.approvedAt, response.paymentKey),
                    orderId = response.orderId,
                    totalAmount = response.totalAmount,
                )
            PARTIAL_CANCELED -> PaymentInquiryResult.PartiallyCanceled
            else -> PaymentInquiryResult.NotApproved(status = response.status)
        }

    // 이미 승인된 뒤라 파싱 실패로 예외를 던지면 클레임이 PENDING으로 남는다. null로 두고 호출 측이 서버 시각으로 대체한다
    private fun parseApprovedAt(
        raw: String?,
        paymentKey: String,
    ): LocalDateTime? {
        if (raw == null) return null
        return try {
            OffsetDateTime.parse(raw).atZoneSameInstant(AppTimeZone.ID).toLocalDateTime()
        } catch (e: DateTimeException) {
            logger.warn { "토스 approvedAt을 해석하지 못해 null로 처리합니다: paymentKey=$paymentKey, approvedAt=$raw, 원인=${e.message}" }
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
        // 사유는 이력과 클라이언트 응답에 실리므로, JSON이 아닌 본문(프록시의 HTML 등)은 원문 대신 상태 코드만 쓴다
        return if (error != null) "${error.code}: ${error.message}" else "HTTP ${e.statusCode.value()}"
    }

    private data class TossPaymentResponse(
        val paymentKey: String,
        val status: String,
        val approvedAt: String? = null,
        val orderId: String? = null,
        val totalAmount: Int? = null,
        val balanceAmount: Int? = null,
        val cancels: List<TossCancel>? = null,
    )

    private data class TossCancel(
        val cancelReason: String? = null,
    )

    private data class TossErrorResponse(
        val code: String,
        val message: String,
    )
}
