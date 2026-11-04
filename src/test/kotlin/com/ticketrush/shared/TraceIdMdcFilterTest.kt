package com.ticketrush.shared

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.Test

class TraceIdMdcFilterTest {
    private val logs = ListAppender<ILoggingEvent>()
    private val filterLogger = LoggerFactory.getLogger(TraceIdMdcFilter::class.java.name) as Logger

    @BeforeEach
    fun captureLogs() {
        logs.start()
        filterLogger.addAppender(logs)
    }

    @AfterEach
    fun releaseLogs() {
        filterLogger.detachAppender(logs)
        logs.stop()
    }

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
    fun `필터를 통과하는 동안 MDC에 clientIp가 채워진다`() {
        val filter = TraceIdMdcFilter(instanceId = "test-instance")
        val request = MockHttpServletRequest().apply { remoteAddr = "203.0.113.7" }
        val response = MockHttpServletResponse()
        var clientIpDuringChain: String? = null

        val chain = FilterChain { _, _ -> clientIpDuringChain = MDC.get("clientIp") }

        filter.doFilter(request, response, chain)

        assertThat(clientIpDuringChain).isEqualTo("203.0.113.7")
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
        assertThat(MDC.get("clientIp")).isNull()
    }

    @Test
    fun `앞단 프록시가 보낸 요청 id가 있으면 traceId로 그대로 쓴다`() {
        val filter = TraceIdMdcFilter(instanceId = "test-instance")
        val request = MockHttpServletRequest().apply { addHeader("X-Request-Id", "0123456789abcdef0123456789abcdef") }
        var traceIdDuringChain: String? = null

        filter.doFilter(request, MockHttpServletResponse(), FilterChain { _, _ -> traceIdDuringChain = MDC.get("traceId") })

        assertThat(traceIdDuringChain).isEqualTo("0123456789abcdef0123456789abcdef")
    }

    @Test
    fun `형식이 맞지 않는 요청 id는 버리고 새 traceId를 만든다`() {
        val filter = TraceIdMdcFilter(instanceId = "test-instance")
        listOf("short", "has space in it", "inject\nline", "a".repeat(65)).forEach { bad ->
            val request = MockHttpServletRequest().apply { addHeader("X-Request-Id", bad) }
            var traceIdDuringChain: String? = null

            filter.doFilter(request, MockHttpServletResponse(), FilterChain { _, _ -> traceIdDuringChain = MDC.get("traceId") })

            assertThat(traceIdDuringChain).`as`(bad).isNotEqualTo(bad).isNotBlank()
        }
    }

    @Test
    fun `기준 시간 이상 걸린 요청은 경로와 상태와 걸린 시간을 WARN으로 남긴다`() {
        val filter = TraceIdMdcFilter(instanceId = "test-instance", slowRequestMs = 0)
        val request =
            MockHttpServletRequest("POST", "/api/reservations/1/payments").apply {
                queryString = "secret=1"
                addHeader("X-Request-Id", "0123456789abcdef0123456789abcdef")
            }
        val response = MockHttpServletResponse().apply { status = 201 }

        filter.doFilter(request, response, FilterChain { _, _ -> })

        val warn = logs.list.single { it.level == Level.WARN }
        assertThat(warn.formattedMessage)
            .contains("method=POST", "uri=/api/reservations/1/payments", "status=201", "elapsedMs=")
            .doesNotContain("secret")
        assertThat(warn.mdcPropertyMap["traceId"]).isEqualTo("0123456789abcdef0123456789abcdef")
    }

    @Test
    fun `기준 시간보다 빨리 끝난 요청은 로그를 남기지 않는다`() {
        val filter = TraceIdMdcFilter(instanceId = "test-instance", slowRequestMs = 60_000)

        filter.doFilter(MockHttpServletRequest(), MockHttpServletResponse(), FilterChain { _, _ -> })

        assertThat(logs.list).isEmpty()
    }

    @Test
    fun `ALB가 붙인 추적 헤더의 Root를 traceId로 쓴다`() {
        val filter = TraceIdMdcFilter(instanceId = "test-instance")
        val request =
            MockHttpServletRequest().apply {
                addHeader("X-Amzn-Trace-Id", "Self=1-67891233-abcdef012345678912345678;Root=1-58337262-36d228ad5d99923122bbe354;Sampled=1")
            }
        var traceIdDuringChain: String? = null

        filter.doFilter(request, MockHttpServletResponse(), FilterChain { _, _ -> traceIdDuringChain = MDC.get("traceId") })

        assertThat(traceIdDuringChain).isEqualTo("1-58337262-36d228ad5d99923122bbe354")
    }

    @Test
    fun `요청 id 헤더와 ALB 추적 헤더가 함께 오면 요청 id를 먼저 쓴다`() {
        val filter = TraceIdMdcFilter(instanceId = "test-instance")
        val request =
            MockHttpServletRequest().apply {
                addHeader("X-Request-Id", "0123456789abcdef0123456789abcdef")
                addHeader("X-Amzn-Trace-Id", "Root=1-58337262-36d228ad5d99923122bbe354")
            }
        var traceIdDuringChain: String? = null

        filter.doFilter(request, MockHttpServletResponse(), FilterChain { _, _ -> traceIdDuringChain = MDC.get("traceId") })

        assertThat(traceIdDuringChain).isEqualTo("0123456789abcdef0123456789abcdef")
    }
}
