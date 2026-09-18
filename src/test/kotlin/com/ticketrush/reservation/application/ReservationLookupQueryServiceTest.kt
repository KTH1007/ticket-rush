package com.ticketrush.reservation.application

import com.ticketrush.event.domain.EventRepositoryPort
import com.ticketrush.reservation.domain.GradeRepositoryPort
import com.ticketrush.reservation.domain.Reservation
import com.ticketrush.reservation.domain.ReservationLookupFailedException
import com.ticketrush.reservation.domain.ReservationLookupRateLimitedException
import com.ticketrush.reservation.domain.ReservationLookupRateLimiterPort
import com.ticketrush.reservation.domain.ReservationRepositoryPort
import com.ticketrush.reservation.domain.ReservationStatus
import com.ticketrush.reservation.domain.Seat
import com.ticketrush.reservation.domain.SeatRepositoryPort
import com.ticketrush.reservation.domain.SeatStatus
import com.ticketrush.shared.PhoneHash
import com.ticketrush.shared.PhoneHasher
import com.ticketrush.support.IntegrationTest
import com.ticketrush.support.공연_하나_저장
import com.ticketrush.support.등급_하나_저장
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID
import kotlin.test.Test

class ReservationLookupQueryServiceTest : IntegrationTest() {
    @Autowired
    lateinit var eventRepository: EventRepositoryPort

    @Autowired
    lateinit var gradeRepository: GradeRepositoryPort

    @Autowired
    lateinit var seatRepository: SeatRepositoryPort

    @Autowired
    lateinit var reservationRepository: ReservationRepositoryPort

    @Autowired
    lateinit var rateLimiter: ReservationLookupRateLimiterPort

    @Autowired
    lateinit var phoneHasher: PhoneHasher

    @Autowired
    lateinit var queryService: ReservationLookupQueryService

    @Test
    fun `reservationNo와 phone이 정확히 일치하면 예약 정보를 반환한다`() {
        // given
        val reservation = 예매완료_예약_준비(reservationNo = "MATCH0000001", phone = "01011112222")

        // when
        val result = queryService.lookup("MATCH0000001", "01011112222")

        // then
        assertThat(result.reservation.id).isEqualTo(reservation.id)
        assertThat(result.seats).hasSize(1)
        assertThat(result.seats.single().gradeName).isEqualTo("VIP")
    }

    @Test
    fun `reservationNo는 맞지만 phone이 틀리면 실패한다`() {
        // given
        예매완료_예약_준비(reservationNo = "WRONGPH0001", phone = "01011112222")

        // when & then
        assertThatThrownBy { queryService.lookup("WRONGPH0001", "01099998888") }
            .isInstanceOf(ReservationLookupFailedException::class.java)
    }

    @Test
    fun `존재하지 않는 reservationNo면 실패한다`() {
        // when & then
        assertThatThrownBy { queryService.lookup("NOTEXIST0001", "01011112222") }
            .isInstanceOf(ReservationLookupFailedException::class.java)
    }

    @Test
    fun `같은 reservationNo로 5번 실패하면 이후 요청은 차단된다`() {
        // given
        repeat(5) {
            assertThatThrownBy { queryService.lookup("BLOCKME00001", "01099999999") }
                .isInstanceOf(ReservationLookupFailedException::class.java)
        }

        // when & then
        assertThatThrownBy { queryService.lookup("BLOCKME00001", "01099999999") }
            .isInstanceOf(ReservationLookupRateLimitedException::class.java)
    }

    @Test
    fun `이미 차단된 reservationNo는 존재 여부와 무관하게 즉시 차단된다`() {
        // given
        val reservation = 예매완료_예약_준비(reservationNo = "ALREADY0001", phone = "01011112222")
        repeat(5) { rateLimiter.tryReserveAttempt("ALREADY0001") }

        // when & then: 정확한 phone을 넣어도 차단이 우선
        assertThatThrownBy { queryService.lookup(reservation.reservationNo!!, "01011112222") }
            .isInstanceOf(ReservationLookupRateLimitedException::class.java)
    }

    @Test
    fun `성공하면 내가 예약한 슬롯만 돌려주고 다른 실패 기록은 그대로 남는다`() {
        // given
        val reservation = 예매완료_예약_준비(reservationNo = "RESETME0001", phone = "01011112222")
        repeat(3) { rateLimiter.tryReserveAttempt("RESETME0001") }

        // when: 조회 자체도 슬롯을 하나 예약했다가(4번째) 성공하면 그 슬롯만 돌려줌 -> 다시 3
        queryService.lookup(reservation.reservationNo!!, "01011112222")

        // then: 무조건 리셋이었다면 5번 다 열렸겠지만, 내 몫만 돌아왔으니 딱 2번만 더 예약 가능(5 - 3)
        assertThat(rateLimiter.tryReserveAttempt("RESETME0001")).isTrue()
        assertThat(rateLimiter.tryReserveAttempt("RESETME0001")).isTrue()
        assertThat(rateLimiter.tryReserveAttempt("RESETME0001")).isFalse()
    }

    private fun 예매완료_예약_준비(
        reservationNo: String,
        phone: String,
    ): Reservation {
        val event = eventRepository.공연_하나_저장()
        val grade = gradeRepository.등급_하나_저장(event, name = "VIP", price = 200_000)
        val phoneHash = phoneHasher.hash(phone)
        val reservation = 결제완료_예약_저장(event.id, phoneHash, reservationNo)
        결제완료_좌석_저장(event.id, grade.id, reservation.id, phoneHash)
        return reservation
    }

    private fun 결제완료_예약_저장(
        eventId: Long,
        phoneHash: PhoneHash,
        reservationNo: String,
    ): Reservation =
        reservationRepository.save(
            Reservation(
                eventId = eventId,
                phoneHash = phoneHash,
                quantity = 1,
                amount = 200_000,
                holdToken = UUID.randomUUID(),
                idempotencyKey = UUID.randomUUID(),
                status = ReservationStatus.PAID,
                reservationNo = reservationNo,
            ),
        )

    private fun 결제완료_좌석_저장(
        eventId: Long,
        gradeId: Long,
        reservationId: Long,
        phoneHash: PhoneHash,
    ): Seat =
        seatRepository.save(
            Seat(
                eventId = eventId,
                gradeId = gradeId,
                section = "A",
                rowLabel = "1",
                seatNo = 1,
                ordinal = 0,
                status = SeatStatus.SOLD,
                reservationId = reservationId,
                phoneHash = phoneHash,
                slotNo = 1,
            ),
        )
}
