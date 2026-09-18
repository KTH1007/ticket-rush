package com.ticketrush.payment.presentation

import com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper
import com.ticketrush.payment.application.PaymentCommandService
import com.ticketrush.payment.domain.Payment
import com.ticketrush.payment.domain.PaymentConfirmationResult
import com.ticketrush.payment.domain.PaymentConflictException
import com.ticketrush.payment.domain.PaymentDeclinedException
import com.ticketrush.payment.domain.PaymentStatus
import com.ticketrush.reservation.domain.HoldTokenMismatchException
import com.ticketrush.reservation.domain.ReservationNotFoundException
import com.ticketrush.reservation.domain.ReservationNotHoldingException
import com.ticketrush.reservation.domain.ReservationStatus
import io.mockk.every
import io.mockk.mockk
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.restdocs.test.autoconfigure.AutoConfigureRestDocs
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.http.MediaType
import org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath
import org.springframework.restdocs.payload.PayloadDocumentation.requestFields
import org.springframework.restdocs.payload.PayloadDocumentation.responseFields
import org.springframework.restdocs.request.RequestDocumentation.parameterWithName
import org.springframework.restdocs.request.RequestDocumentation.pathParameters
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.Test

@WebMvcTest(PaymentController::class)
@AutoConfigureRestDocs
class PaymentControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var paymentCommandService: PaymentCommandService

    @TestConfiguration
    class MockConfig {
        @Bean
        fun paymentCommandService(): PaymentCommandService = mockk()
    }

    @Test
    fun `결제를 확정하면 201과 결제 결과를 반환한다`() {
        // given
        val holdToken = UUID.randomUUID()
        every { paymentCommandService.confirmPayment(1L, holdToken) } returns 결제_확정_결과()

        // when & then
        mockMvc
            .perform(결제_확정_요청(holdToken = holdToken))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.reservationNo").value("RESNO000001"))
            .andExpect(jsonPath("$.amount").value(100_000))
            .andDo(
                MockMvcRestDocumentationWrapper.document(
                    "payment-confirm",
                    summary = "결제 확정",
                    description = "홀드된 예약의 결제를 확정한다.",
                    snippets = arrayOf(paymentConfirmPathParameters, paymentConfirmRequestFields, paymentConfirmResponseFields),
                ),
            )
    }

    @Test
    fun `존재하지 않는 예약이면 404를 반환한다`() {
        // given
        val holdToken = UUID.randomUUID()
        every { paymentCommandService.confirmPayment(1L, holdToken) } throws ReservationNotFoundException(1L)

        // when & then
        mockMvc.perform(결제_확정_요청(holdToken = holdToken)).andExpect(status().isNotFound)
    }

    @Test
    fun `holdToken이 일치하지 않으면 401을 반환한다`() {
        // given
        val holdToken = UUID.randomUUID()
        every { paymentCommandService.confirmPayment(1L, holdToken) } throws HoldTokenMismatchException()

        // when & then
        mockMvc.perform(결제_확정_요청(holdToken = holdToken)).andExpect(status().isUnauthorized)
    }

    @Test
    fun `HOLDING이 아닌 예약이면 409를 반환한다`() {
        // given
        val holdToken = UUID.randomUUID()
        every {
            paymentCommandService.confirmPayment(1L, holdToken)
        } throws ReservationNotHoldingException(ReservationStatus.EXPIRED)

        // when & then
        mockMvc.perform(결제_확정_요청(holdToken = holdToken)).andExpect(status().isConflict)
    }

    @Test
    fun `PG가 거절하면 409를 반환한다`() {
        // given
        val holdToken = UUID.randomUUID()
        every { paymentCommandService.confirmPayment(1L, holdToken) } throws PaymentDeclinedException("한도 초과")

        // when & then
        mockMvc.perform(결제_확정_요청(holdToken = holdToken)).andExpect(status().isConflict)
    }

    @Test
    fun `정확히 동시에 처리된 요청이면 409를 반환한다`() {
        // given
        val holdToken = UUID.randomUUID()
        every { paymentCommandService.confirmPayment(1L, holdToken) } throws PaymentConflictException()

        // when & then
        mockMvc.perform(결제_확정_요청(holdToken = holdToken)).andExpect(status().isConflict)
    }

    private fun 결제_확정_요청(
        reservationId: Long = 1L,
        holdToken: UUID,
    ) = post("/api/reservations/{reservationId}/payments", reservationId)
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(PaymentConfirmationRequest(holdToken = holdToken)))

    private fun 결제_확정_결과(): PaymentConfirmationResult =
        PaymentConfirmationResult(
            payment =
                Payment(
                    id = 1L,
                    reservationId = 1L,
                    amount = 100_000,
                    pgTransactionId = "PG-TXN-1",
                    status = PaymentStatus.SUCCESS,
                    paidAt = LocalDateTime.of(2026, 1, 1, 12, 0),
                ),
            reservationNo = "RESNO000001",
        )

    companion object {
        private val paymentConfirmPathParameters = pathParameters(parameterWithName("reservationId").description("예약 id"))
        private val paymentConfirmRequestFields =
            requestFields(
                fieldWithPath("holdToken").description("좌석 홀드 시 발급받은 소유권 증명 토큰"),
            )
        private val paymentConfirmResponseFields =
            responseFields(
                fieldWithPath("reservationId").description("예약 id"),
                fieldWithPath("reservationNo").description("예매번호"),
                fieldWithPath("amount").description("결제 금액"),
                fieldWithPath("paidAt").description("결제 확정 시각"),
            )
    }
}
