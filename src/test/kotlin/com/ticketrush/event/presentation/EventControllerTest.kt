package com.ticketrush.event.presentation

import com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper
import com.ticketrush.event.application.EventQueryService
import com.ticketrush.event.domain.EventNotFoundException
import com.ticketrush.shared.response.PageResponse
import io.mockk.every
import io.mockk.mockk
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.restdocs.test.autoconfigure.AutoConfigureRestDocs
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath
import org.springframework.restdocs.payload.PayloadDocumentation.responseFields
import org.springframework.restdocs.request.RequestDocumentation.parameterWithName
import org.springframework.restdocs.request.RequestDocumentation.pathParameters
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime
import kotlin.test.Test

@WebMvcTest(EventController::class)
@AutoConfigureRestDocs
class EventControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var eventQueryService: EventQueryService

    @TestConfiguration
    class MockConfig {
        @Bean
        fun eventQueryService(): EventQueryService = mockk()
    }

    @Test
    fun `공연 목록을 조회하면 페이지네이션된 응답을 반환한다`() {
        // given
        val summary =
            EventSummaryResponse(
                id = 1L,
                title = "아이유 콘서트",
                venue = "잠실종합운동장",
                opensAt = LocalDateTime.of(2026, 9, 1, 10, 0),
                startsAt = LocalDateTime.of(2026, 9, 20, 19, 0),
            )
        every { eventQueryService.findEvents(any()) } returns
            PageResponse(content = listOf(summary), page = 0, size = 10, totalElements = 1, totalPages = 1)

        // when & then
        mockMvc
            .perform(get("/api/events"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content[0].title").value("아이유 콘서트"))
            .andExpect(jsonPath("$.totalElements").value(1))
            .andDo(MockMvcRestDocumentationWrapper.document("event-list", snippets = arrayOf(eventListResponseFields)))
    }

    @Test
    fun `공연 상세를 조회하면 등급 목록과 함께 반환한다`() {
        // given
        every { eventQueryService.findEvent(1L) } returns
            공연_상세(grades = listOf(GradeResponse(name = "VIP", price = 200_000)))

        // when & then
        mockMvc
            .perform(get("/api/events/{id}", 1L))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.grades[0].name").value("VIP"))
            .andDo(
                MockMvcRestDocumentationWrapper.document(
                    "event-detail",
                    snippets = arrayOf(eventDetailPathParameters, eventDetailResponseFields),
                ),
            )
    }

    @Test
    fun `존재하지 않는 공연 id로 상세 조회하면 404를 반환한다`() {
        // given
        every { eventQueryService.findEvent(999L) } throws EventNotFoundException(999L)

        // when & then
        mockMvc
            .perform(get("/api/events/{id}", 999L))
            .andExpect(status().isNotFound)
    }

    // 필드 설명과 픽스처가 길어 테스트 메서드 본문과 분리했다.
    companion object {
        private fun 공연_상세(grades: List<GradeResponse>) =
            EventDetailResponse(
                id = 1L,
                title = "아이유 콘서트",
                venue = "잠실종합운동장",
                description = "2026 아이유 콘서트",
                opensAt = LocalDateTime.of(2026, 9, 1, 10, 0),
                startsAt = LocalDateTime.of(2026, 9, 20, 19, 0),
                grades = grades,
            )

        private val eventListResponseFields =
            responseFields(
                fieldWithPath("content[]").description("공연 목록"),
                fieldWithPath("content[].id").description("공연 id"),
                fieldWithPath("content[].title").description("공연명"),
                fieldWithPath("content[].venue").description("공연장"),
                fieldWithPath("content[].opensAt").description("예매 오픈 시각"),
                fieldWithPath("content[].startsAt").description("공연 시작 시각"),
                fieldWithPath("page").description("현재 페이지 (0부터 시작)"),
                fieldWithPath("size").description("페이지 크기"),
                fieldWithPath("totalElements").description("전체 공연 수"),
                fieldWithPath("totalPages").description("전체 페이지 수"),
                fieldWithPath("hasNext").description("다음 페이지 존재 여부"),
            )

        private val eventDetailPathParameters = pathParameters(parameterWithName("id").description("공연 id"))

        private val eventDetailResponseFields =
            responseFields(
                fieldWithPath("id").description("공연 id"),
                fieldWithPath("title").description("공연명"),
                fieldWithPath("venue").description("공연장"),
                fieldWithPath("description").description("공연 설명"),
                fieldWithPath("opensAt").description("예매 오픈 시각"),
                fieldWithPath("startsAt").description("공연 시작 시각"),
                fieldWithPath("grades[]").description("등급 목록"),
                fieldWithPath("grades[].name").description("등급명"),
                fieldWithPath("grades[].price").description("가격"),
            )
    }
}
