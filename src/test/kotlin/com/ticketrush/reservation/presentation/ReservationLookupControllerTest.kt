package com.ticketrush.reservation.presentation

import com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper
import com.ticketrush.reservation.application.ReservationLookupQueryService
import com.ticketrush.reservation.domain.Reservation
import com.ticketrush.reservation.domain.ReservationLookupFailedException
import com.ticketrush.reservation.domain.ReservationLookupRateLimitedException
import com.ticketrush.reservation.domain.ReservationLookupResult
import com.ticketrush.reservation.domain.ReservationLookupSeat
import com.ticketrush.reservation.domain.ReservationStatus
import com.ticketrush.shared.ClientIp
import com.ticketrush.shared.PhoneHash
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.UUID
import kotlin.test.Test

@WebMvcTest(ReservationLookupController::class)
@AutoConfigureRestDocs
class ReservationLookupControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var queryService: ReservationLookupQueryService

    @TestConfiguration
    class MockConfig {
        @Bean
        fun reservationLookupQueryService(): ReservationLookupQueryService = mockk()
    }

    @Test
    fun `조회에 성공하면 200과 예약 정보를 반환한다`() {
        // given
        every { queryService.lookup("RESVA2345678", "01012345678", any()) } returns 조회_결과()

        // when & then
        mockMvc
            .perform(조회_요청(reservationNo = "RESVA2345678", phone = "01012345678"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.reservationNo").value("RESVA2345678"))
            .andExpect(jsonPath("$.seats[0].gradeName").value("VIP"))
            .andDo(
                MockMvcRestDocumentationWrapper.document(
                    "reservation-lookup",
                    summary = "예매 조회",
                    description = "예매번호와 전화번호로 예약을 조회한다.",
                    snippets = arrayOf(lookupRequestFields, lookupResponseFields),
                ),
            )
    }

    @Test
    fun `예매번호 또는 전화번호가 일치하지 않으면 404를 반환한다`() {
        // given
        every { queryService.lookup("WRNGA2345678", "01012345678", any()) } throws ReservationLookupFailedException()

        // when & then
        mockMvc.perform(조회_요청(reservationNo = "WRNGA2345678", phone = "01012345678")).andExpect(status().isNotFound)
    }

    @Test
    fun `반복 실패로 차단되면 429를 반환한다`() {
        // given
        every { queryService.lookup("BLKDA2345678", "01012345678", any()) } throws ReservationLookupRateLimitedException()

        // when & then
        mockMvc.perform(조회_요청(reservationNo = "BLKDA2345678", phone = "01012345678")).andExpect(status().isTooManyRequests)
    }

    @Test
    fun `차단 때 남은 시간을 알면 Retry-After를 초 단위로 올려 준다`() {
        // given
        every { queryService.lookup("BLKDA2345678", "01012345678", any()) } throws
            ReservationLookupRateLimitedException(Duration.ofMillis(59_100))

        // when & then
        mockMvc
            .perform(조회_요청(reservationNo = "BLKDA2345678", phone = "01012345678"))
            .andExpect(status().isTooManyRequests)
            .andExpect(header().string("Retry-After", "60"))
    }

    @Test
    fun `요청의 접속 IP를 서비스에 넘긴다`() {
        // given
        every { queryService.lookup("RESVA2345678", "01012345678", ClientIp("198.51.100.9")) } returns 조회_결과()

        // when & then
        mockMvc
            .perform(조회_요청(reservationNo = "RESVA2345678", phone = "01012345678").with { it.apply { remoteAddr = "198.51.100.9" } })
            .andExpect(status().isOk)
    }

    @Test
    fun `휴대폰 번호 형식이 아니면 400을 반환한다`() {
        // when & then
        mockMvc.perform(조회_요청(reservationNo = "RESVA2345678", phone = "abcd")).andExpect(status().isBadRequest)
    }

    // 예매번호가 그대로 Redis 레이트리밋 키에 들어가므로, 형식과 길이가 틀린 값은 키를 만들기 전에 거른다
    @Test
    fun `예매번호 형식이 아니면 400을 반환하고 서비스를 호출하지 않는다`() {
        // given
        val invalid =
            listOf("abcdefgh2345", "ABCDEFGH234", "ABCDEFGH23456", "ABCDEFGHO234", "ABCDEFGH2341", "ABCDEFGH 234", "A".repeat(5_000))

        // when & then
        invalid.forEach { mockMvc.perform(조회_요청(reservationNo = it, phone = "01012345678")).andExpect(status().isBadRequest) }
        invalid.forEach { verify(exactly = 0) { queryService.lookup(it, any(), any()) } }
    }

    private fun 조회_요청(
        reservationNo: String,
        phone: String,
    ) = post("/api/reservations/lookup")
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(ReservationLookupRequest(reservationNo = reservationNo, phone = phone)))

    private fun 조회_결과(): ReservationLookupResult =
        ReservationLookupResult(
            reservation =
                Reservation(
                    id = 1L,
                    eventId = 10L,
                    phoneHash = PhoneHash(ByteArray(32) { 1 }),
                    quantity = 1,
                    amount = 200_000,
                    holdToken = UUID.randomUUID(),
                    idempotencyKey = UUID.randomUUID(),
                    status = ReservationStatus.PAID,
                    reservationNo = "RESVA2345678",
                ),
            seats = listOf(ReservationLookupSeat(section = "A", rowLabel = "1", seatNo = 5, gradeName = "VIP")),
        )

    companion object {
        private val lookupRequestFields =
            requestFields(
                fieldWithPath("reservationNo").description("예매번호"),
                fieldWithPath("phone").description("예약 시 등록한 휴대폰 번호"),
            )
        private val lookupResponseFields =
            responseFields(
                fieldWithPath("reservationId").description("예약 id"),
                fieldWithPath("eventId").description("공연 id"),
                fieldWithPath("reservationNo").description("예매번호"),
                fieldWithPath("status").description("예약 상태"),
                fieldWithPath("statusDescription").description("예약 상태 설명"),
                fieldWithPath("amount").description("결제 금액"),
                fieldWithPath("seats[].section").description("구역"),
                fieldWithPath("seats[].rowLabel").description("열"),
                fieldWithPath("seats[].seatNo").description("좌석 번호"),
                fieldWithPath("seats[].gradeName").description("좌석 등급명"),
            )
    }
}
