package com.ticketrush.reservation.presentation

import com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper
import com.ticketrush.queue.application.QueueQueryService
import com.ticketrush.queue.domain.InvalidQueueTokenException
import com.ticketrush.queue.domain.QueueNotActiveException
import com.ticketrush.reservation.application.ReservationCommandService
import com.ticketrush.reservation.domain.Reservation
import com.ticketrush.reservation.domain.SeatAlreadyHeldException
import com.ticketrush.reservation.domain.SeatSelection
import com.ticketrush.shared.PhoneHash
import com.ticketrush.shared.PhoneHasher
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.restdocs.test.autoconfigure.AutoConfigureRestDocs
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.http.MediaType
import org.springframework.restdocs.headers.HeaderDocumentation.headerWithName
import org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders
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

@WebMvcTest(SeatHoldController::class)
@AutoConfigureRestDocs
class SeatHoldControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var reservationCommandService: ReservationCommandService

    @Autowired
    lateinit var phoneHasher: PhoneHasher

    @Autowired
    lateinit var queueQueryService: QueueQueryService

    @TestConfiguration
    class MockConfig {
        @Bean
        fun reservationCommandService(): ReservationCommandService = mockk()

        @Bean
        fun phoneHasher(): PhoneHasher = mockk()

        @Bean
        fun queueQueryService(): QueueQueryService = mockk()
    }

    @Test
    fun `좌석을 홀드하면 201과 예약 정보를 반환한다`() {
        // given
        대기열_통과_처리()
        val phoneHash = PhoneHash(ByteArray(32) { 1 })
        every { phoneHasher.hash("01012345678") } returns phoneHash
        every {
            reservationCommandService.holdSeats(eventId = 1L, seatSelection = SeatSelection(listOf(10L)), phoneHash = phoneHash)
        } returns 예약(seatIds = listOf(10L), phoneHash = phoneHash)

        // when & then
        mockMvc
            .perform(홀드_요청(seatIds = listOf(10L)))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.quantity").value(1))
            .andExpect(jsonPath("$.amount").value(100_000))
            .andDo(좌석_홀드_문서화())
    }

    @Test
    fun `이미 선점된 좌석이면 409를 반환한다`() {
        // given
        대기열_통과_처리()
        val phoneHash = PhoneHash(ByteArray(32) { 1 })
        every { phoneHasher.hash("01012345678") } returns phoneHash
        every {
            reservationCommandService.holdSeats(eventId = 1L, seatSelection = SeatSelection(listOf(10L)), phoneHash = phoneHash)
        } throws SeatAlreadyHeldException()

        // when & then
        mockMvc.perform(홀드_요청(seatIds = listOf(10L))).andExpect(status().isConflict)
    }

    @Test
    fun `좌석을 하나도 선택하지 않으면 400을 반환한다`() {
        // when & then
        mockMvc.perform(홀드_요청(seatIds = emptyList())).andExpect(status().isBadRequest)
    }

    @Test
    fun `좌석을 3개 이상 선택하면 400을 반환한다`() {
        // when & then
        mockMvc.perform(홀드_요청(seatIds = listOf(10L, 11L, 12L))).andExpect(status().isBadRequest)
    }

    @Test
    fun `전화번호 형식이 올바르지 않으면 400을 반환한다`() {
        // when & then
        mockMvc.perform(홀드_요청(seatIds = listOf(10L), phoneNumber = "010-1234-5678")).andExpect(status().isBadRequest)
    }

    @Test
    fun `대기열 토큰이 없으면 401을 반환한다`() {
        // given
        every { queueQueryService.requireActive(1L, null) } throws InvalidQueueTokenException()

        // when & then
        mockMvc.perform(홀드_요청(seatIds = listOf(10L), queueToken = null)).andExpect(status().isUnauthorized)
    }

    @Test
    fun `대기열 토큰이 유효하지 않으면 401을 반환한다`() {
        // given
        every { queueQueryService.requireActive(1L, "invalid-token") } throws InvalidQueueTokenException()

        // when & then
        mockMvc.perform(홀드_요청(seatIds = listOf(10L), queueToken = "invalid-token")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `아직 대기열 순서가 아니면 403을 반환한다`() {
        // given
        every { queueQueryService.requireActive(1L, "waiting-token") } throws QueueNotActiveException()

        // when & then
        mockMvc.perform(홀드_요청(seatIds = listOf(10L), queueToken = "waiting-token")).andExpect(status().isForbidden)
    }

    private fun 대기열_통과_처리(token: String = "test-queue-token") {
        every { queueQueryService.requireActive(1L, token) } just Runs
    }

    private fun 좌석_홀드_문서화() =
        MockMvcRestDocumentationWrapper.document(
            "seat-hold",
            summary = "좌석 홀드",
            description = "좌석 1~2석을 선점하고 예약을 생성한다.",
            snippets = arrayOf(seatHoldPathParameters, seatHoldRequestHeaders, seatHoldRequestFields, seatHoldResponseFields),
        )

    private fun 홀드_요청(
        eventId: Long = 1L,
        seatIds: List<Long>,
        phoneNumber: String = "01012345678",
        queueToken: String? = "test-queue-token",
    ) = post("/api/events/{eventId}/seats/hold", eventId)
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(SeatHoldRequest(seatIds = seatIds, phoneNumber = phoneNumber)))
        .let { if (queueToken != null) it.header("X-Queue-Token", queueToken) else it }

    private fun 예약(
        seatIds: List<Long>,
        phoneHash: PhoneHash,
    ): Reservation =
        Reservation(
            id = 1L,
            eventId = 1L,
            phoneHash = phoneHash,
            quantity = seatIds.size.toShort(),
            amount = 100_000,
            holdToken = UUID.randomUUID(),
            idempotencyKey = UUID.randomUUID(),
            holdExpiresAt = LocalDateTime.of(2030, 1, 1, 0, 0),
        )

    companion object {
        private val seatHoldPathParameters = pathParameters(parameterWithName("eventId").description("공연 id"))
        private val seatHoldRequestHeaders =
            requestHeaders(headerWithName("X-Queue-Token").description("대기열 등록 시 발급받은 토큰 (Active 상태여야 함)"))
        private val seatHoldRequestFields =
            requestFields(
                fieldWithPath("seatIds").description("선택한 좌석 id 목록 (1~2개)"),
                fieldWithPath("phoneNumber").description("전화번호 (해싱 후 저장, 원문 미저장)"),
            )
        private val seatHoldResponseFields =
            responseFields(
                fieldWithPath("reservationId").description("예약 id"),
                fieldWithPath("holdToken").description("홀드 소유권 증명 토큰 (결제 확정 시 재사용)"),
                fieldWithPath("quantity").description("선택 좌석 수"),
                fieldWithPath("amount").description("결제 금액"),
                fieldWithPath("holdExpiresAt").description("홀드 만료 시각"),
            )
    }
}
