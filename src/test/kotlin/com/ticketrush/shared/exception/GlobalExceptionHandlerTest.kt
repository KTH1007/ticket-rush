package com.ticketrush.shared.exception

import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import tools.jackson.databind.ObjectMapper
import kotlin.test.Test

@WebMvcTest(ExceptionTestController::class)
class GlobalExceptionHandlerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Test
    fun `NotFoundException 발생 시 404와 ProblemDetail을 반환한다`() {
        // when
        val result = mockMvc.perform(post("/test/not-found")).andReturn()

        // then
        assertThat(result.response.status).isEqualTo(404)
        val body = objectMapper.readValue(result.response.contentAsString, ProblemDetail::class.java)
        assertThat(body.title).isEqualTo("Not Found")
        assertThat(body.detail).isEqualTo("샘플을 찾을 수 없습니다")
        assertThat(body.status).isEqualTo(404)
        assertThat(body.properties?.get("code")).isEqualTo("SAMPLE_NOT_FOUND")
    }

    @Test
    fun `NotFoundException이 아닌 BusinessException 발생 시 400과 ProblemDetail을 반환한다`() {
        // when
        val result = mockMvc.perform(post("/test/business")).andReturn()

        // then
        assertThat(result.response.status).isEqualTo(400)
        val body = objectMapper.readValue(result.response.contentAsString, ProblemDetail::class.java)
        assertThat(body.title).isEqualTo("Bad Request")
        assertThat(body.detail).isEqualTo("샘플 충돌이 발생했습니다")
        assertThat(body.properties?.get("code")).isEqualTo("SAMPLE_CONFLICT")
    }

    @Test
    fun `Bean Validation 실패 시 400과 필드별 에러를 반환한다`() {
        // given
        val invalidRequest = objectMapper.writeValueAsString(SampleRequest(name = ""))

        // when
        val result =
            mockMvc
                .perform(
                    post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidRequest),
                ).andReturn()

        // then
        assertThat(result.response.status).isEqualTo(400)
        val body = objectMapper.readValue(result.response.contentAsString, ProblemDetail::class.java)
        assertThat(body.properties?.get("code")).isEqualTo("INVALID_REQUEST")
        @Suppress("UNCHECKED_CAST")
        val fieldErrors = body.properties?.get("fieldErrors") as List<Map<String, String>>
        assertThat(fieldErrors).extracting("field").containsExactly("name")
    }

    @Test
    fun `처리되지 않은 예외는 500과 ProblemDetail을 반환한다`() {
        // when
        val result = mockMvc.perform(post("/test/unexpected")).andReturn()

        // then
        assertThat(result.response.status).isEqualTo(500)
        val body = objectMapper.readValue(result.response.contentAsString, ProblemDetail::class.java)
        assertThat(body.properties?.get("code")).isEqualTo("INTERNAL_SERVER_ERROR")
        assertThat(body.detail).isEqualTo("일시적인 오류가 발생했습니다")
    }

    @Test
    fun `잘못된 HTTP 메서드로 요청하면 500이 아닌 405를 반환한다`() {
        // when: not-found는 POST 전용인데 GET으로 요청
        val result = mockMvc.perform(get("/test/not-found")).andReturn()

        // then
        assertThat(result.response.status).isEqualTo(405)
    }

    @Test
    fun `존재하지 않는 URL로 요청하면 500이 아닌 404를 반환한다`() {
        // when
        val result = mockMvc.perform(post("/test/does-not-exist")).andReturn()

        // then
        assertThat(result.response.status).isEqualTo(404)
    }

    @Test
    fun `에러 응답에 traceId가 포함된다`() {
        // when
        val result = mockMvc.perform(post("/test/not-found")).andReturn()

        // then
        val body = objectMapper.readValue(result.response.contentAsString, ProblemDetail::class.java)
        assertThat(body.properties?.get("traceId") as String?).isNotBlank()
    }
}
