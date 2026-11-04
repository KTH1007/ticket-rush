package com.ticketrush.e2e

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

// E28: 읽기 경로. 공연 목록, 상세, 좌석 상태, 좌석 배치
class ReadPathsE2eTest : E2eTest() {
    @Test
    fun `E28 공연 목록과 상세와 좌석 상태와 좌석 배치를 조회한다`() {
        val event = seed.openEvent(seatCount = 4)

        val list = api.get("/api/events")
        val detail = api.get("/api/events/${event.eventId}")
        val status = api.get("/api/events/${event.eventId}/seats/status")
        val layout = api.get("/api/events/${event.eventId}/seats/layout")
        val missing = api.get("/api/events/$NO_SUCH_EVENT")

        assertThat(list.status).`as`(list.body.take(200)).isEqualTo(HTTP_OK)
        assertThat(list.body).contains("e2e 공연")
        assertThat(detail.status).`as`(detail.body).isEqualTo(HTTP_OK)
        assertThat(detail.json().path("title").asString(null)).isEqualTo("e2e 공연")
        assertThat(status.status).`as`(status.body.take(200)).isEqualTo(HTTP_OK)
        assertThat(layout.status).`as`(layout.body.take(200)).isEqualTo(HTTP_OK)
        assertThat(layout.body).isNotBlank()
        assertThat(missing.status).`as`(missing.body).isEqualTo(404)
        assertThat(missing.code).isEqualTo("EVENT_NOT_FOUND")
    }

    @Test
    fun `E30 요청 지연이 SLO 임계값 버킷으로 지표에 남는다`() {
        val event = seed.openEvent(seatCount = 2)
        api.get("/api/events/${event.eventId}")

        val metrics = api.get("/actuator/prometheus")

        assertThat(metrics.status).isEqualTo(HTTP_OK)
        assertThat(metrics.body).contains("""http_server_requests_seconds_bucket""")
        assertThat(metrics.body).contains("""uri="/api/events/{id}"""")
        assertThat(metrics.body).contains("""le="0.3"""")
    }

    private companion object {
        const val NO_SUCH_EVENT = 987_654_321L
    }
}
