package com.ticketrush.event.presentation

import com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper
import com.ticketrush.event.application.EventQueryService
import com.ticketrush.event.application.SeatLayoutSnapshot
import com.ticketrush.event.application.SeatMapQueryService
import com.ticketrush.event.domain.EventNotFoundException
import com.ticketrush.shared.response.PageResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.restdocs.test.autoconfigure.AutoConfigureRestDocs
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpHeaders.ACCEPT_ENCODING
import org.springframework.http.HttpHeaders.CACHE_CONTROL
import org.springframework.http.HttpHeaders.CONTENT_ENCODING
import org.springframework.http.HttpHeaders.ETAG
import org.springframework.http.HttpHeaders.IF_NONE_MATCH
import org.springframework.http.HttpHeaders.VARY
import org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath
import org.springframework.restdocs.payload.PayloadDocumentation.responseFields
import org.springframework.restdocs.request.RequestDocumentation.parameterWithName
import org.springframework.restdocs.request.RequestDocumentation.pathParameters
import org.springframework.restdocs.request.RequestDocumentation.queryParameters
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.LocalDateTime
import java.util.zip.GZIPInputStream
import kotlin.test.Test

@WebMvcTest(EventController::class)
@AutoConfigureRestDocs
class EventControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var eventQueryService: EventQueryService

    @Autowired
    lateinit var seatMapQueryService: SeatMapQueryService

    @TestConfiguration
    class MockConfig {
        @Bean
        fun eventQueryService(): EventQueryService = mockk()

        @Bean
        fun seatMapQueryService(): SeatMapQueryService = mockk()
    }

    @Test
    fun `공연 목록을 조회하면 페이지네이션된 응답을 반환한다`() {
        // given
        every { eventQueryService.findEvents(any()) } returns 공연_목록()

        // when & then
        mockMvc
            .perform(get("/api/events"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content[0].title").value("아이유 콘서트"))
            .andExpect(jsonPath("$.totalElements").value(1))
            .andDo(
                MockMvcRestDocumentationWrapper.document(
                    "event-list",
                    summary = "공연 목록 조회",
                    description = "페이지네이션된 공연 목록을 조회한다.",
                    snippets = arrayOf(eventListQueryParameters, eventListResponseFields),
                ),
            )
    }

    @Test
    fun `정렬 파라미터 없이 조회하면 id 기준 기본 정렬이 적용된다`() {
        // given
        every { eventQueryService.findEvents(any()) } returns 공연_목록()

        // when
        mockMvc.perform(get("/api/events")).andExpect(status().isOk)

        // then
        val pageable = slot<Pageable>()
        verify { eventQueryService.findEvents(capture(pageable)) }
        assertThat(pageable.captured.sort.isSorted).isTrue()
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
                    summary = "공연 상세 조회",
                    description = "공연 상세 정보와 등급 목록을 조회한다.",
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

    @Test
    fun `좌석 배치를 gzip으로 받을 수 있으면 압축된 바이트와 ETag, 캐시 헤더를 반환한다`() {
        // given
        val layout = 배치_스냅샷()
        every { seatMapQueryService.findLayout(1L) } returns layout

        // when & then
        val response =
            mockMvc
                .perform(get("/api/events/{id}/seats/layout", 1L).header(ACCEPT_ENCODING, "gzip"))
                .andExpect(status().isOk)
                .andExpect(header().string(CONTENT_ENCODING, "gzip"))
                .andExpect(header().string(ETAG, "\"${layout.etag}\""))
                .andExpect(header().string(CACHE_CONTROL, "max-age=60, public"))
                .andExpect(header().string(VARY, ACCEPT_ENCODING))
                .andReturn()
                .response
        assertThat(GZIPInputStream(ByteArrayInputStream(response.contentAsByteArray)).readBytes()).isEqualTo(layout.json())
    }

    // gzip;q=0은 gzip 거부다. 그래도 압축 본문을 주면 클라이언트가 해석하지 못한다
    @Test
    fun `좌석 배치를 gzip 거부로 요청하면 풀린 JSON을 반환한다`() {
        // given
        every { seatMapQueryService.findLayout(1L) } returns 배치_스냅샷()

        // when & then
        mockMvc
            .perform(get("/api/events/{id}/seats/layout", 1L).header(ACCEPT_ENCODING, "gzip;q=0, deflate"))
            .andExpect(status().isOk)
            .andExpect(header().doesNotExist(CONTENT_ENCODING))
            .andExpect(jsonPath("$.seatCount").value(1))
    }

    @Test
    fun `좌석 배치를 gzip 없이 받으면 풀린 JSON을 반환한다`() {
        // given
        every { seatMapQueryService.findLayout(1L) } returns 배치_스냅샷()

        // when & then
        mockMvc
            .perform(get("/api/events/{id}/seats/layout", 1L))
            .andExpect(status().isOk)
            .andExpect(header().doesNotExist(CONTENT_ENCODING))
            .andExpect(jsonPath("$.seats[0].ordinal").value(0))
            .andExpect(jsonPath("$.grades[0].name").value("VIP"))
            .andDo(
                MockMvcRestDocumentationWrapper.document(
                    "event-seat-layout",
                    summary = "좌석 배치 조회",
                    description =
                        "공연의 좌석 배치(구역, 열, 번호, 등급, ordinal)를 조회한다. " +
                            "거의 바뀌지 않아 ETag와 Cache-Control로 캐시하고, Accept-Encoding: gzip이면 압축해서 준다.",
                    snippets = arrayOf(eventSeatLayoutPathParameters, eventSeatLayoutResponseFields),
                ),
            )
    }

    @Test
    fun `If-None-Match가 ETag와 같으면 304를 반환한다`() {
        // given
        val layout = 배치_스냅샷()
        every { seatMapQueryService.findLayout(1L) } returns layout

        // when & then
        mockMvc
            .perform(get("/api/events/{id}/seats/layout", 1L).header(IF_NONE_MATCH, "\"${layout.etag}\""))
            .andExpect(status().isNotModified)
    }

    @Test
    fun `존재하지 않는 공연 id로 좌석 배치를 조회하면 404를 반환한다`() {
        // given
        every { seatMapQueryService.findLayout(999L) } throws EventNotFoundException(999L)

        // when & then
        mockMvc
            .perform(get("/api/events/{id}/seats/layout", 999L))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `좌석 상태를 조회하면 비트맵 스냅샷을 반환하고 1초 동안 캐시할 수 있다`() {
        // given
        every { seatMapQueryService.findStatus(1L) } returns 좌석_상태()

        // when & then
        mockMvc
            .perform(get("/api/events/{id}/seats/status", 1L))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "max-age=1, public"))
            .andExpect(jsonPath("$.seatCount").value(2))
            .andExpect(jsonPath("$.bitsPerSeat").value(2))
            .andExpect(jsonPath("$.bitmap").value("QA=="))
            .andDo(
                MockMvcRestDocumentationWrapper.document(
                    "event-seat-status",
                    summary = "좌석 상태 조회",
                    description = "공연의 좌석 상태를 ordinal 순서의 비트맵으로 조회한다. 좌석 배치는 따로 받아 ordinal로 맞춘다.",
                    snippets = arrayOf(eventSeatStatusPathParameters, eventSeatStatusResponseFields),
                ),
            )
    }

    @Test
    fun `존재하지 않는 공연 id로 좌석 상태를 조회하면 404를 반환한다`() {
        // given
        every { seatMapQueryService.findStatus(999L) } throws EventNotFoundException(999L)

        // when & then
        mockMvc
            .perform(get("/api/events/{id}/seats/status", 999L))
            .andExpect(status().isNotFound)
    }

    // 필드 설명과 픽스처가 길어 테스트 메서드 본문과 분리했다.
    companion object {
        private fun 공연_목록(): PageResponse<EventSummaryResponse> =
            PageResponse(
                content =
                    listOf(
                        EventSummaryResponse(
                            id = 1L,
                            title = "아이유 콘서트",
                            venue = "잠실종합운동장",
                            opensAt = LocalDateTime.of(2026, 9, 1, 10, 0),
                            startsAt = LocalDateTime.of(2026, 9, 20, 19, 0),
                        ),
                    ),
                page = 0,
                size = 10,
                totalElements = 1,
                totalPages = 1,
            )

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

        private val eventListQueryParameters =
            queryParameters(
                parameterWithName("page").description("조회할 페이지 (0부터 시작, 기본값 0)").optional(),
                parameterWithName("size").description("페이지 크기 (기본값 20)").optional(),
                parameterWithName("sort").description("정렬 기준 (예: title,asc)").optional(),
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

        private fun 배치_스냅샷() =
            SeatLayoutSnapshot.from(
                eventId = 1L,
                builtAt = Instant.parse("2026-10-11T11:15:03Z"),
                json =
                    """
                    {"eventId":1,"seatCount":1,"grades":[{"id":7,"name":"VIP"}],
                     "seats":[{"id":100,"ordinal":0,"section":"A","rowLabel":"1","seatNo":1,"gradeId":7}]}
                    """.trimIndent().toByteArray(),
            )

        private val eventSeatLayoutPathParameters = pathParameters(parameterWithName("id").description("공연 id"))

        private val eventSeatLayoutResponseFields =
            responseFields(
                fieldWithPath("eventId").description("공연 id"),
                fieldWithPath("seatCount").description("좌석 수. 가장 큰 ordinal + 1"),
                fieldWithPath("grades[]").description("등급 표"),
                fieldWithPath("grades[].id").description("등급 id"),
                fieldWithPath("grades[].name").description("등급명"),
                fieldWithPath("seats[]").description("좌석 목록. ordinal 순서"),
                fieldWithPath("seats[].id").description("좌석 id. 홀드 요청에 쓴다"),
                fieldWithPath("seats[].ordinal").description("상태 비트맵에서 이 좌석의 위치"),
                fieldWithPath("seats[].section").description("구역"),
                fieldWithPath("seats[].rowLabel").description("열"),
                fieldWithPath("seats[].seatNo").description("좌석 번호"),
                fieldWithPath("seats[].gradeId").description("등급 id. 등급 표에서 이름을 찾는다"),
            )

        private fun 좌석_상태() =
            SeatStatusResponse(
                eventId = 1L,
                seatCount = 2,
                bitsPerSeat = 2,
                builtAt = Instant.parse("2026-10-11T11:15:03Z"),
                bitmap = "QA==",
            )

        private val eventSeatStatusPathParameters = pathParameters(parameterWithName("id").description("공연 id"))

        private val eventSeatStatusResponseFields =
            responseFields(
                fieldWithPath("eventId").description("공연 id"),
                fieldWithPath("seatCount").description("좌석 수. 가장 큰 ordinal + 1"),
                fieldWithPath("bitsPerSeat").description("좌석당 비트 수"),
                fieldWithPath("builtAt").description("이 상태를 만든 시각. 지금과의 차이가 지도의 나이"),
                fieldWithPath("bitmap").description(
                    "base64 비트맵. ordinal i의 상태는 바이트[i/4]의 상위 비트부터 (i%4)번째 2비트 (0 가능, 1 홀드, 2 판매)",
                ),
            )
    }
}
