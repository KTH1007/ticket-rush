package com.ticketrush.event.application

import com.ticketrush.event.domain.EventNotFoundException
import com.ticketrush.event.domain.EventRepositoryPort
import com.ticketrush.reservation.domain.GradeRepositoryPort
import com.ticketrush.reservation.domain.ReservationRepositoryPort
import com.ticketrush.reservation.domain.SeatRepositoryPort
import com.ticketrush.reservation.domain.SeatStatus
import com.ticketrush.support.IntegrationTest
import com.ticketrush.support.공연_하나_저장
import com.ticketrush.support.등급_하나_저장
import com.ticketrush.support.예약_하나_저장
import com.ticketrush.support.좌석_하나_저장
import com.ticketrush.support.홀드된_좌석_하나_저장
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper
import java.util.Base64
import kotlin.test.Test

class SeatMapQueryServiceTest : IntegrationTest() {
    @Autowired
    lateinit var seatMapQueryService: SeatMapQueryService

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var eventRepository: EventRepositoryPort

    @Autowired
    lateinit var gradeRepository: GradeRepositoryPort

    @Autowired
    lateinit var seatRepository: SeatRepositoryPort

    @Autowired
    lateinit var reservationRepository: ReservationRepositoryPort

    // 클라이언트가 같은 규칙으로 읽는다는 것을 고정하려고 인코더와 따로 둔 디코더
    private fun statusOf(
        bitmap: String,
        ordinal: Int,
    ): SeatStatus {
        val bytes = Base64.getDecoder().decode(bitmap)
        return when (val code = (bytes[ordinal / 4].toInt() shr ((3 - ordinal % 4) * 2)) and 0b11) {
            0 -> SeatStatus.AVAILABLE
            1 -> SeatStatus.HELD
            2 -> SeatStatus.SOLD
            else -> error("알 수 없는 상태 코드: $code")
        }
    }

    @Test
    fun `가능, 홀드, 판매 좌석을 조회하면 비트맵을 디코딩했을 때 저장한 상태와 같다`() {
        // given
        val event = eventRepository.공연_하나_저장()
        val grade = gradeRepository.등급_하나_저장(event)
        val reservation = reservationRepository.예약_하나_저장(event)
        seatRepository.좌석_하나_저장(event, grade, seatNo = 10, ordinal = 0)
        seatRepository.홀드된_좌석_하나_저장(event, grade, reservation.id, reservation.phoneHash, slotNo = 1, seatNo = 1)
        seatRepository.홀드된_좌석_하나_저장(event, grade, reservation.id, reservation.phoneHash, slotNo = 2, seatNo = 2, status = SeatStatus.SOLD)

        // when
        val response = seatMapQueryService.findStatus(event.id)

        // then
        assertThat(response.eventId).isEqualTo(event.id)
        assertThat(response.seatCount).isEqualTo(3)
        assertThat(response.bitsPerSeat).isEqualTo(2)
        assertThat(statusOf(response.bitmap, 0)).isEqualTo(SeatStatus.AVAILABLE)
        assertThat(statusOf(response.bitmap, 1)).isEqualTo(SeatStatus.HELD)
        assertThat(statusOf(response.bitmap, 2)).isEqualTo(SeatStatus.SOLD)
    }

    // 읽기 요청이 DB를 쓰지 않는다는 것이 이 캐시의 존재 이유다
    @Test
    fun `연달아 조회하면 같은 스냅샷이다`() {
        // given
        val event = eventRepository.공연_하나_저장()
        val grade = gradeRepository.등급_하나_저장(event)
        seatRepository.좌석_하나_저장(event, grade, ordinal = 0)

        // when
        val first = seatMapQueryService.findStatus(event.id)
        seatRepository.좌석_하나_저장(event, grade, seatNo = 2, ordinal = 1)
        val second = seatMapQueryService.findStatus(event.id)

        // then
        assertThat(second.builtAt).isEqualTo(first.builtAt)
        assertThat(second.seatCount).isEqualTo(1)
    }

    @Test
    fun `존재하지 않는 공연이면 EventNotFoundException이 발생한다`() {
        assertThatThrownBy { seatMapQueryService.findStatus(Long.MAX_VALUE) }.isInstanceOf(EventNotFoundException::class.java)
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `배치를 조회하면 저장한 좌석이 ordinal 순서로 등급 표와 함께 담긴다`() {
        // given
        val event = eventRepository.공연_하나_저장()
        val vip = gradeRepository.등급_하나_저장(event, name = "VIP")
        val second = seatRepository.좌석_하나_저장(event, vip, rowLabel = "2", seatNo = 1, ordinal = 1)
        val first = seatRepository.좌석_하나_저장(event, vip, rowLabel = "1", seatNo = 1, ordinal = 0)

        // when
        val layout = seatMapQueryService.findLayout(event.id)

        // then
        val body = objectMapper.readValue(layout.json(), Map::class.java) as Map<String, Any>
        assertThat(body["grades"] as List<*>).containsExactly(mapOf("id" to vip.id.toInt(), "name" to "VIP"))
        assertThat((body["seats"] as List<Map<String, Any>>).map { it["id"] }).containsExactly(first.id.toInt(), second.id.toInt())
    }

    @Test
    fun `존재하지 않는 공연의 배치를 조회하면 EventNotFoundException이 발생한다`() {
        assertThatThrownBy { seatMapQueryService.findLayout(Long.MAX_VALUE) }.isInstanceOf(EventNotFoundException::class.java)
    }
}
