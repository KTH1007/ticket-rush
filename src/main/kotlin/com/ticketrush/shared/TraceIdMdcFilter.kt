package com.ticketrush.shared

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Component
class TraceIdMdcFilter(
    @Value("\${INSTANCE_ID:local}") private val instanceId: String,
    @Value("\${ticket-rush.observability.slow-request-ms:$DEFAULT_SLOW_REQUEST_MS}")
    private val slowRequestMs: Long = DEFAULT_SLOW_REQUEST_MS,
) : Filter {
    override fun doFilter(
        request: ServletRequest,
        response: ServletResponse,
        chain: FilterChain,
    ) {
        val startedNanos = System.nanoTime()
        try {
            MDC.put("traceId", traceIdOf(request))
            MDC.put("instanceId", instanceId)
            (request as? HttpServletRequest)?.let { MDC.put("clientIp", ClientIp.from(it).value) }
            chain.doFilter(request, response)
        } finally {
            logIfSlow(request, response, startedNanos)
            MDC.clear()
        }
    }

    // 앞단이 붙인 요청 id(nginx의 X-Request-Id, ALB의 X-Amzn-Trace-Id Root)를 그대로 써서 그쪽 로그와 앱 로그를 같은 id로 찾는다.
    // 직접 들어온 값이 로그를 오염시키지 않게 형식을 본다
    private fun traceIdOf(request: ServletRequest): String {
        val http = request as? HttpServletRequest
        val incoming =
            http?.getHeader(REQUEST_ID_HEADER)
                ?: http?.getHeader(AMZN_TRACE_HEADER)?.let { AMZN_ROOT.find(it)?.groupValues?.get(1) }
        return incoming?.takeIf { REQUEST_ID_FORMAT.matches(it) } ?: UUID.randomUUID().toString()
    }

    // 쿼리 문자열은 남기지 않는다(경로만)
    private fun logIfSlow(
        request: ServletRequest,
        response: ServletResponse,
        startedNanos: Long,
    ) {
        val elapsedMs = (System.nanoTime() - startedNanos) / NANOS_PER_MILLI
        val http = request as? HttpServletRequest
        if (elapsedMs < slowRequestMs || http == null) return
        val status = (response as? HttpServletResponse)?.status
        logger.warn { "느린 요청: method=${http.method}, uri=${http.requestURI}, status=$status, elapsedMs=$elapsedMs" }
    }

    companion object {
        const val REQUEST_ID_HEADER = "X-Request-Id"
        const val AMZN_TRACE_HEADER = "X-Amzn-Trace-Id"
        const val DEFAULT_SLOW_REQUEST_MS = 300L
        private const val NANOS_PER_MILLI = 1_000_000L
        private val REQUEST_ID_FORMAT = Regex("[0-9A-Za-z-]{8,64}")
        private val AMZN_ROOT = Regex("Root=([^;]+)")
    }
}
