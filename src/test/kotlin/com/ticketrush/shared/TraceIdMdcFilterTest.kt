package com.ticketrush.shared

import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.Test

class TraceIdMdcFilterTest {
    @Test
    fun `필터를 통과하는 동안 MDC에 traceId가 채워진다`() {
        val filter = TraceIdMdcFilter(instanceId = "test-instance")
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()
        var traceIdDuringChain: String? = null

        // FilterChain은 자바 함수형 인터페이스라 람다로 바로 SAM 변환됨
        val chain =
            FilterChain { _, _ ->
                traceIdDuringChain = MDC.get("traceId")
            }

        filter.doFilter(request, response, chain)

        assertThat(traceIdDuringChain).isNotBlank()
    }

    @Test
    fun `필터를 통과하는 동안 MDC에 instanceId가 채워진다`() {
        val filter = TraceIdMdcFilter(instanceId = "test-instance")
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()
        var instanceIdDuringChain: String? = null

        val chain =
            FilterChain { _, _ ->
                instanceIdDuringChain = MDC.get("instanceId")
            }

        filter.doFilter(request, response, chain)

        assertThat(instanceIdDuringChain).isEqualTo("test-instance")
    }

    @Test
    fun `요청이 끝나면 MDC가 정리된다`() {
        val filter = TraceIdMdcFilter(instanceId = "test-instance")
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()
        // 체인만 통과시키고 아무것도 안 함
        val chain = FilterChain { _, _ -> }

        filter.doFilter(request, response, chain)

        assertThat(MDC.get("traceId")).isNull()
        assertThat(MDC.get("instanceId")).isNull()
    }
}
