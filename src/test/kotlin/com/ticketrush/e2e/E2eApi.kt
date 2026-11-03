package com.ticketrush.e2e

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

// 실제 HTTP로 앱을 호출하는 얇은 클라이언트. 요청마다 X-Forwarded-For로 사용자 IP를 정한다
class E2eApi(
    private val baseUrl: String,
) {
    private val http =
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build()
    private val mapper = JsonMapper.builder().build()

    inner class Response(
        val status: Int,
        private val headers: Map<String, List<String>>,
        val body: String,
    ) {
        fun header(name: String): String? =
            headers.entries
                .firstOrNull { it.key.equals(name, ignoreCase = true) }
                ?.value
                ?.firstOrNull()

        fun json(): JsonNode = mapper.readTree(body)

        // 오류 응답 본문의 code. 성공 응답이면 null
        val code: String?
            get() = runCatching { json().path("code").asString(null) }.getOrNull()
    }

    fun get(
        path: String,
        ip: String = DEFAULT_IP,
        headers: Map<String, String> = emptyMap(),
    ): Response = send(builder(path, ip, headers).GET().build())

    fun post(
        path: String,
        body: Map<String, Any?>? = null,
        ip: String = DEFAULT_IP,
        headers: Map<String, String> = emptyMap(),
    ): Response {
        val json = if (body == null) "" else mapper.writeValueAsString(body)
        val request =
            builder(path, ip, headers + ("Content-Type" to "application/json"))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build()
        return send(request)
    }

    // ---- 사용자 흐름 ----

    fun register(
        eventId: Long,
        ip: String,
    ): Response = post("/api/events/$eventId/queues", ip = ip)

    fun poll(
        eventId: Long,
        token: String,
        ip: String,
    ): Response = get("/api/events/$eventId/queues/me", ip, mapOf(QUEUE_TOKEN to token))

    fun hold(
        eventId: Long,
        token: String,
        seatIds: List<Long>,
        phone: String,
        ip: String,
    ): Response =
        post(
            "/api/events/$eventId/seats/hold",
            mapOf("seatIds" to seatIds, "phoneNumber" to phone),
            ip,
            mapOf(QUEUE_TOKEN to token),
        )

    fun pay(
        hold: JsonNode,
        paymentKey: String,
        ip: String,
        holdToken: String? = hold.path("holdToken").asString(null),
    ): Response =
        post(
            "/api/reservations/${hold.path("reservationId").asLong()}/payments",
            mapOf(
                "holdToken" to holdToken,
                "paymentKey" to paymentKey,
                "orderId" to hold.path("orderId").asString(null),
                "amount" to hold.path("amount").asInt(),
            ),
            ip,
        )

    fun lookup(
        reservationNo: String,
        phone: String,
        ip: String,
    ): Response = post("/api/reservations/lookup", mapOf("reservationNo" to reservationNo, "phone" to phone), ip)

    fun cancel(
        reservationNo: String,
        phone: String,
        ip: String,
    ): Response = post("/api/reservations/cancel", mapOf("reservationNo" to reservationNo, "phone" to phone), ip)

    // 승격될 때까지 순번을 조회한다(rank 0이 활성). 제한 시간 안에 안 되면 마지막 응답을 돌려준다
    fun awaitActive(
        eventId: Long,
        token: String,
        ip: String,
        timeout: Duration = Duration.ofSeconds(10),
    ): Response {
        val deadline = System.nanoTime() + timeout.toNanos()
        var last = poll(eventId, token, ip)
        while (!(last.status == 200 && last.json().path("rank").asLong(-1) == 0L) && System.nanoTime() < deadline) {
            Thread.sleep(POLL_INTERVAL_MILLIS)
            last = poll(eventId, token, ip)
        }
        return last
    }

    private fun builder(
        path: String,
        ip: String,
        headers: Map<String, String>,
    ): HttpRequest.Builder {
        val builder =
            HttpRequest
                .newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .header("X-Forwarded-For", ip)
        headers.forEach { (name, value) -> builder.header(name, value) }
        return builder
    }

    private fun send(request: HttpRequest): Response {
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        return Response(response.statusCode(), response.headers().map(), response.body())
    }

    companion object {
        const val QUEUE_TOKEN = "X-Queue-Token"
        const val DEFAULT_IP = "203.0.113.1"
        private const val POLL_INTERVAL_MILLIS = 100L
        private const val REQUEST_TIMEOUT_SECONDS = 15L
    }
}
