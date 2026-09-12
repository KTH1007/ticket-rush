package com.ticketrush.reservation.application

import com.ticketrush.event.domain.Event
import com.ticketrush.event.domain.EventRepositoryPort
import com.ticketrush.reservation.domain.Grade
import com.ticketrush.reservation.domain.GradeRepositoryPort
import com.ticketrush.reservation.domain.Reservation
import com.ticketrush.reservation.domain.ReservationLimitExceededException
import com.ticketrush.reservation.domain.Seat
import com.ticketrush.reservation.domain.SeatAlreadyHeldException
import com.ticketrush.reservation.domain.SeatRepositoryPort
import com.ticketrush.reservation.domain.SeatSelection
import com.ticketrush.shared.PhoneHash
import com.ticketrush.support.IntegrationTest
import com.ticketrush.support.공연_하나_저장
import com.ticketrush.support.등급_하나_저장
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Tag("concurrency")
class ReservationCommandServiceConcurrencyTest : IntegrationTest() {
    @Autowired
    lateinit var eventRepository: EventRepositoryPort

    @Autowired
    lateinit var gradeRepository: GradeRepositoryPort

    @Autowired
    lateinit var seatRepository: SeatRepositoryPort

    @Autowired
    lateinit var reservationCommandService: ReservationCommandService

    @Autowired
    lateinit var redisTemplate: StringRedisTemplate

    @RepeatedTest(5)
    fun `같은 좌석에 동시 요청이 몰리면 정확히 하나만 성공한다`() {
        // given
        val event = eventRepository.공연_하나_저장()
        val grade = gradeRepository.등급_하나_저장(event)
        val seat = seatRepository.save(좌석(event, grade, seatNo = 1))
        val threadCount = 5

        // when
        val results =
            동시_홀드_시도(threadCount) { i ->
                reservationCommandService.holdSeats(
                    eventId = event.id,
                    seatSelection = SeatSelection(listOf(seat.id)),
                    phoneHash = PhoneHash(ByteArray(32) { i.toByte() }),
                )
            }

        // then
        val failures = results.mapNotNull { it.exceptionOrNull() }
        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        assertThat(failures).hasSize(threadCount - 1)
        assertThat(failures).allSatisfy { assertThat(it).isInstanceOf(SeatAlreadyHeldException::class.java) }
    }

    @RepeatedTest(5)
    fun `같은 전화번호가 서로 다른 좌석을 동시에 요청하면 하나만 성공하고 나머지 Redis 클레임은 해제된다`() {
        // given
        val (event, seat1, seat2) = 같은_등급_좌석_두개_준비()
        val phoneHash = PhoneHash(ByteArray(32) { 1 })

        // when
        val results = 서로_다른_좌석_동시_홀드_시도(event.id, seat1.id, seat2.id, phoneHash)

        // then
        val failures = results.mapNotNull { it.exceptionOrNull() }
        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        assertThat(failures).hasSize(1)
        assertThat(failures.single()).isInstanceOf(ReservationLimitExceededException::class.java)
        검증_하나만_홀드되고_나머지는_클레임_해제됨(event.id, seat1.id, seat2.id)
    }

    private fun 같은_등급_좌석_두개_준비(): Triple<Event, Seat, Seat> {
        val event = eventRepository.공연_하나_저장()
        val grade = gradeRepository.등급_하나_저장(event)
        val seat1 = seatRepository.save(좌석(event, grade, seatNo = 1))
        val seat2 = seatRepository.save(좌석(event, grade, seatNo = 2))
        return Triple(event, seat1, seat2)
    }

    private fun 서로_다른_좌석_동시_홀드_시도(
        eventId: Long,
        seatId1: Long,
        seatId2: Long,
        phoneHash: PhoneHash,
    ): List<Result<Reservation>> {
        val seatIdsPerThread = listOf(listOf(seatId1), listOf(seatId2))
        return 동시_홀드_시도(seatIdsPerThread.size) { i ->
            reservationCommandService.holdSeats(
                eventId = eventId,
                seatSelection = SeatSelection(seatIdsPerThread[i]),
                phoneHash = phoneHash,
            )
        }
    }

    private fun 검증_하나만_홀드되고_나머지는_클레임_해제됨(
        eventId: Long,
        seatId1: Long,
        seatId2: Long,
    ) {
        val seats = listOf(seatId1, seatId2).map { id -> seatRepository.findAllByEventId(eventId).single { it.id == id } }
        assertThat(seats.count { it.status.name == "HELD" }).isEqualTo(1)

        // 진 쪽의 Redis 클레임이 release로 실제로 풀렸는지 확인 (Critical 1/2 수정 검증)
        val loserSeatId = seats.single { it.status.name == "AVAILABLE" }.id
        assertThat(redisTemplate.opsForValue().get("seat:hold:$eventId:$loserSeatId")).isNull()
    }

    private fun 동시_홀드_시도(
        threadCount: Int,
        action: (Int) -> Reservation,
    ): List<Result<Reservation>> {
        val startGate = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)
        val results = Collections.synchronizedList(mutableListOf<Result<Reservation>>())

        repeat(threadCount) { i ->
            executor.submit {
                startGate.await()
                results.add(runCatching { action(i) })
                doneLatch.countDown()
            }
        }
        startGate.countDown()
        doneLatch.await(10, TimeUnit.SECONDS)
        executor.shutdown()
        return results
    }

    private fun 좌석(
        event: Event,
        grade: Grade,
        seatNo: Short,
    ): Seat = Seat(eventId = event.id, gradeId = grade.id, section = "A", rowLabel = "1", seatNo = seatNo, ordinal = seatNo.toInt())
}
