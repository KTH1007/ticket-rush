package com.ticketrush.payment.presentation

import com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper
import com.ticketrush.payment.application.PaymentCancelCommandService
import com.ticketrush.payment.domain.Payment
import com.ticketrush.payment.domain.PaymentCancelResult
import com.ticketrush.payment.domain.PaymentConflictException
import com.ticketrush.payment.domain.PaymentStatus
import com.ticketrush.reservation.domain.ReservationAlreadyCanceledException
import com.ticketrush.reservation.domain.ReservationLookupFailedException
import com.ticketrush.reservation.domain.ReservationLookupRateLimitedException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.restdocs.test.autoconfigure.AutoConfigureRestDocs
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.http.MediaType
import org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath
import org.springframework.restdocs.payload.PayloadDocumentation.requestFields
import org.springframework.restdocs.payload.PayloadDocumentation.responseFields
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import kotlin.test.Test

@WebMvcTest(PaymentCancelController::class)
@AutoConfigureRestDocs
class PaymentCancelControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var cancelService: PaymentCancelCommandService

    @TestConfiguration
    class MockConfig {
        @Bean
        fun paymentCancelCommandService(): PaymentCancelCommandService = mockk()
    }

    @Test
    fun `취소에 성공하면 200과 취소 결과를 반환한다`() {
        // given
        every { cancelService.cancel("RESVA2345678", "01012345678") } returns 취소_결과()

        // when & then
        mockMvc
            .perform(취소_요청(reservationNo = "RESVA2345678", phone = "01012345678"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.paymentStatus").value("REFUNDED"))
            .andDo(
                MockMvcRestDocumentationWrapper.document(
                    "payment-cancel",
                    summary = "결제 취소/환불",
                    description = "예매번호와 전화번호로 본인 예약을 취소하고 환불을 시도한다.",
                    snippets = arrayOf(cancelRequestFields, cancelResponseFields),
                ),
            )
    }

    @Test
    fun `reservationNo 또는 phone이 일치하지 않으면 404를 반환한다`() {
        // given
        every { cancelService.cancel("WRNGA2345678", "01012345678") } throws ReservationLookupFailedException()

        // when & then
        mockMvc.perform(취소_요청(reservationNo = "WRNGA2345678", phone = "01012345678")).andExpect(status().isNotFound)
    }

    @Test
    fun `반복 실패로 차단되면 429를 반환한다`() {
        // given
        every { cancelService.cancel("BLKDA2345678", "01012345678") } throws ReservationLookupRateLimitedException()

        // when & then
        mockMvc.perform(취소_요청(reservationNo = "BLKDA2345678", phone = "01012345678")).andExpect(status().isTooManyRequests)
    }

    @Test
    fun `이미 취소된 예약이면 409를 반환한다`() {
        // given
        every { cancelService.cancel("ALRDA2345678", "01012345678") } throws ReservationAlreadyCanceledException()

        // when & then
        mockMvc.perform(취소_요청(reservationNo = "ALRDA2345678", phone = "01012345678")).andExpect(status().isConflict)
    }

    @Test
    fun `동시 처리 충돌이면 409를 반환한다`() {
        // given
        every { cancelService.cancel("RACEA2345678", "01012345678") } throws PaymentConflictException()

        // when & then
        mockMvc.perform(취소_요청(reservationNo = "RACEA2345678", phone = "01012345678")).andExpect(status().isConflict)
    }

    @Test
    fun `휴대폰 번호 형식이 아니면 400을 반환한다`() {
        // when & then
        mockMvc.perform(취소_요청(reservationNo = "RESVA2345678", phone = "abcd")).andExpect(status().isBadRequest)
    }

    // 예매번호가 그대로 Redis 레이트리밋 키에 들어가므로, 형식과 길이가 틀린 값은 키를 만들기 전에 거른다
    @Test
    fun `예매번호 형식이 아니면 400을 반환하고 서비스를 호출하지 않는다`() {
        // given
        val invalid =
            listOf("abcdefgh2345", "ABCDEFGH234", "ABCDEFGH23456", "ABCDEFGHO234", "ABCDEFGH2341", "ABCDEFGH 234", "A".repeat(5_000))

        // when & then
        invalid.forEach { mockMvc.perform(취소_요청(reservationNo = it, phone = "01012345678")).andExpect(status().isBadRequest) }
        invalid.forEach { verify(exactly = 0) { cancelService.cancel(it, any()) } }
    }

    private fun 취소_요청(
        reservationNo: String,
        phone: String,
    ) = post("/api/reservations/cancel")
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(PaymentCancelRequest(reservationNo = reservationNo, phone = phone)))

    private fun 취소_결과(): PaymentCancelResult =
        PaymentCancelResult(
            payment =
                Payment(
                    id = 1L,
                    reservationId = 1L,
                    amount = 200_000,
                    pgTransactionId = "PG-TXN-1",
                    status = PaymentStatus.REFUNDED,
                ),
            reservationNo = "RESVA2345678",
        )

    companion object {
        private val cancelRequestFields =
            requestFields(
                fieldWithPath("reservationNo").description("예매번호"),
                fieldWithPath("phone").description("예약 시 등록한 휴대폰 번호"),
            )
        private val cancelResponseFields =
            responseFields(
                fieldWithPath("reservationNo").description("예매번호"),
                fieldWithPath("paymentStatus").description("결제 상태(CANCELED: 취소 접수, REFUNDED: 환불 완료)"),
                fieldWithPath("paymentStatusDescription").description("결제 상태 설명"),
                fieldWithPath("amount").description("결제 금액"),
            )
    }
}
