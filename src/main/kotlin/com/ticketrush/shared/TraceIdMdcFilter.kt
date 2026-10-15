package com.ticketrush.shared

import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class TraceIdMdcFilter(
    @Value("\${INSTANCE_ID:local}") private val instanceId: String,
) : Filter {
    override fun doFilter(
        request: ServletRequest,
        response: ServletResponse,
        chain: FilterChain,
    ) {
        try {
            MDC.put("traceId", UUID.randomUUID().toString())
            MDC.put("instanceId", instanceId)
            (request as? HttpServletRequest)?.let { MDC.put("clientIp", ClientIp.from(it).value) }
            chain.doFilter(request, response)
        } finally {
            MDC.clear()
        }
    }
}
