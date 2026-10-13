package com.ticketrush.queue.presentation

import com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper
import com.ticketrush.queue.application.QueueCommandService
import com.ticketrush.queue.application.QueueQueryService
import com.ticketrush.queue.domain.InvalidQueueTokenException
import com.ticketrush.queue.domain.QueueEventNotFoundException
import com.ticketrush.queue.domain.QueueNotOpenException
import io.mockk.every
import io.mockk.mockk
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.restdocs.test.autoconfigure.AutoConfigureRestDocs
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.restdocs.headers.HeaderDocumentation.headerWithName
import org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders
import org.springframework.restdocs.headers.HeaderDocumentation.responseHeaders
import org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath
import org.springframework.restdocs.payload.PayloadDocumentation.responseFields
import org.springframework.restdocs.request.RequestDocumentation.parameterWithName
import org.springframework.restdocs.request.RequestDocumentation.pathParameters
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime
import kotlin.test.Test

@WebMvcTest(QueueController::class)
@AutoConfigureRestDocs
class QueueControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var queueCommandService: QueueCommandService

    @Autowired
    lateinit var queueQueryService: QueueQueryService

    @TestConfiguration
    class MockConfig {
        @Bean
        fun queueCommandService(): QueueCommandService = mockk()

        @Bean
        fun queueQueryService(): QueueQueryService = mockk()
    }

    @Test
    fun `대기열에 등록하면 201과 함께 토큰 헤더를 반환한다`() {
        // given
        every { queueCommandService.register(1L) } returns "test-token"

        // when & then
        mockMvc
            .perform(post("/api/events/{eventId}/queues", 1L))
            .andExpect(status().isCreated)
            .andExpect(header().string("X-Queue-Token", "test-token"))
            .andDo(
                MockMvcRestDocumentationWrapper.document(
                    "queue-register",
                    summary = "대기열 등록",
                    description =
                        "공연 대기열에 등록하고 토큰을 발급받는다. " +
                            "입구가 혼잡하면 503과 Retry-After(3 ~ 10초)가 오며, 그 시간 뒤에 다시 등록한다(처음 누른 순서는 보장되지 않는다).",
                    snippets = arrayOf(queueRegisterPathParameters, queueRegisterResponseHeaders),
                ),
            )
    }

    // 없는 이벤트는 404, 있는데 아직 오픈 전이면 409로 구분해야 클라이언트가 "공연 없음"과 "오픈 전 안내"를 나눠 보여줄 수 있다
    @Test
    fun `없는 이벤트에 등록하면 404와 EVENT_NOT_FOUND를 반환한다`() {
        every { queueCommandService.register(404L) } throws QueueEventNotFoundException(404L)

        mockMvc
            .perform(post("/api/events/{eventId}/queues", 404L))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"))
    }

    @Test
    fun `오픈 전 이벤트에 등록하면 409와 QUEUE_NOT_OPEN을 반환한다`() {
        every { queueCommandService.register(10L) } throws QueueNotOpenException(10L, LocalDateTime.of(2026, 10, 11, 10, 0))

        mockMvc
            .perform(post("/api/events/{eventId}/queues", 10L))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("QUEUE_NOT_OPEN"))
    }

    @Test
    fun `순번을 조회하면 rank와 폴링 간격을 반환한다`() {
        // given
        every { queueQueryService.checkStatus(1L, "test-token") } returns
            QueueStatusResponse(rank = 5, nextPollIntervalMs = 2000)

        // when & then
        mockMvc
            .perform(get("/api/events/{eventId}/queues/me", 1L).header("X-Queue-Token", "test-token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.rank").value(5))
            .andExpect(jsonPath("$.nextPollIntervalMs").value(2000))
            .andDo(
                MockMvcRestDocumentationWrapper.document(
                    "queue-status",
                    summary = "대기열 순번 조회",
                    description =
                        "현재 대기 순번과 다음 폴링 주기를 조회한다. " +
                            "입구가 혼잡해 503이 오면 Retry-After와 nextPollIntervalMs 중 큰 값만큼 기다린 뒤 다시 조회한다(순번은 유지된다).",
                    snippets = arrayOf(queueStatusPathParameters, queueStatusRequestHeaders, queueStatusResponseFields),
                ),
            )
    }

    @Test
    fun `유효하지 않은 토큰으로 조회하면 401을 반환한다`() {
        // given
        every { queueQueryService.checkStatus(1L, "invalid-token") } throws InvalidQueueTokenException()

        // when & then
        mockMvc
            .perform(get("/api/events/{eventId}/queues/me", 1L).header("X-Queue-Token", "invalid-token"))
            .andExpect(status().isUnauthorized)
    }

    companion object {
        private val queueRegisterPathParameters = pathParameters(parameterWithName("eventId").description("공연 id"))
        private val queueRegisterResponseHeaders =
            responseHeaders(headerWithName("X-Queue-Token").description("대기열 토큰 (TTL 15분)"))

        private val queueStatusPathParameters = pathParameters(parameterWithName("eventId").description("공연 id"))
        private val queueStatusRequestHeaders =
            requestHeaders(headerWithName("X-Queue-Token").description("대기열 등록 시 발급받은 토큰"))
        private val queueStatusResponseFields =
            responseFields(
                fieldWithPath("rank").description("대기 순번 (0이면 Active)"),
                fieldWithPath("nextPollIntervalMs").description("다음 폴링까지 대기할 시간(ms)"),
            )
    }
}
