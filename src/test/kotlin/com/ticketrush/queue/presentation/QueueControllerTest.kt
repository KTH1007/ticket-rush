package com.ticketrush.queue.presentation

import com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper
import com.ticketrush.queue.application.QueueCommandService
import com.ticketrush.queue.application.QueueQueryService
import com.ticketrush.queue.domain.InvalidQueueTokenException
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
                    description = "공연 대기열에 등록하고 토큰을 발급받는다.",
                    snippets = arrayOf(queueRegisterPathParameters, queueRegisterResponseHeaders),
                ),
            )
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
                    description = "현재 대기 순번과 다음 폴링 주기를 조회한다.",
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
