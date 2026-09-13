package com.ticketrush.reservation.application

import com.ticketrush.event.domain.EventRepositoryPort
import com.ticketrush.reservation.domain.GradeRepositoryPort
import com.ticketrush.reservation.domain.Reservation
import com.ticketrush.reservation.domain.ReservationRepositoryPort
import com.ticketrush.reservation.domain.ReservationStatus
import com.ticketrush.reservation.domain.Seat
import com.ticketrush.reservation.domain.SeatRepositoryPort
import com.ticketrush.reservation.domain.SeatStatus
import com.ticketrush.shared.PhoneHash
import com.ticketrush.support.IntegrationTest
import com.ticketrush.support.공연_하나_저장
import com.ticketrush.support.등급_하나_저장
import com.ticketrush.support.예약_하나_저장
import com.ticketrush.support.홀드된_좌석_하나_저장
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDateTime
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// 여러 인스턴스에서 스케줄러가 동시에 돌아도(또는 같은 인스턴스가 겹쳐 실행돼도) 같은 행이
// 중복 처리되지 않는지 확인한다. 조건부 UPDATE 자체가 자기 자신에 대해 멱등이라는 걸
// 순차 재실행이 아니라 실제 동시 실행으로 검증한다.
@Tag("concurrency")
class HoldExpirySchedulerConcurrencyTest : IntegrationTest() {
    @Autowired
    lateinit var eventRepository: EventRepositoryPort

    @Autowired
    lateinit var gradeRepository: GradeRepositoryPort

    @Autowired
    lateinit var seatRepository: SeatRepositoryPort

    @Autowired
    lateinit var reservationRepository: ReservationRepositoryPort

    @Autowired
    lateinit var holdExpiryScheduler: HoldExpiryScheduler

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @RepeatedTest(5)
    fun `여러 인스턴스가 동시에 스윕해도 같은 좌석과 예약은 한 번만 갱신된다`() {
        // given
        val (reservation, seat) = 만료된_홀드_준비()

        // when
        val results = 동시_스윕(threadCount = 5)

        // then
        검증_한_번만_갱신됨(results, reservation, seat)
    }

    private fun 만료된_홀드_준비(): Pair<Reservation, Seat> {
        val event = eventRepository.공연_하나_저장()
        val grade = gradeRepository.등급_하나_저장(event)
        val phoneHash = PhoneHash(ByteArray(32) { 1 })
        val reservation =
            reservationRepository.예약_하나_저장(event, phoneHash = phoneHash, holdExpiresAt = EXPIRED_HOLD_EXPIRES_AT)
        val seat =
            seatRepository.홀드된_좌석_하나_저장(
                event,
                grade,
                reservationId = reservation.id,
                phoneHash = phoneHash,
                slotNo = 1,
                holdExpiresAt = EXPIRED_HOLD_EXPIRES_AT,
            )
        return reservation to seat
    }

    private fun 검증_한_번만_갱신됨(
        results: List<Result<Unit>>,
        reservation: Reservation,
        seat: Seat,
    ) {
        assertThat(results).allSatisfy { assertThat(it.isFailure).isFalse() }
        val revertedSeat = seatRepository.findAllByEventId(seat.eventId).single { it.id == seat.id }
        assertThat(revertedSeat.status).isEqualTo(SeatStatus.AVAILABLE)
        assertThat(revertedSeat.version).isEqualTo(seat.version + 1)
        val (status, version) = 예약_상태와_버전_조회(reservation.id)
        assertThat(status).isEqualTo(ReservationStatus.EXPIRED)
        assertThat(version).isEqualTo(reservation.version + 1)
    }

    private fun 동시_스윕(threadCount: Int): List<Result<Unit>> {
        val startGate = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)
        val results = Collections.synchronizedList(mutableListOf<Result<Unit>>())

        repeat(threadCount) {
            executor.submit {
                startGate.await()
                results.add(runCatching { holdExpiryScheduler.sweep() })
                doneLatch.countDown()
            }
        }
        startGate.countDown()
        doneLatch.await(10, TimeUnit.SECONDS)
        executor.shutdown()
        return results
    }

    private fun 예약_상태와_버전_조회(id: Long): Pair<ReservationStatus, Long> {
        val row = jdbcTemplate.queryForMap("SELECT status, version FROM reservation WHERE id = ?", id)
        return ReservationStatus.valueOf(row["status"] as String) to (row["version"] as Number).toLong()
    }

    companion object {
        private val EXPIRED_HOLD_EXPIRES_AT: LocalDateTime = LocalDateTime.of(2020, 1, 1, 0, 0)
    }
}
