package com.ticketrush.reservation.application

import com.ticketrush.event.domain.EventRepositoryPort
import com.ticketrush.payment.application.PaymentClaimService
import com.ticketrush.payment.domain.PaymentRepositoryPort
import com.ticketrush.payment.domain.PaymentStatus
import com.ticketrush.reservation.SeatPolicyProperties
import com.ticketrush.reservation.domain.GradeRepositoryPort
import com.ticketrush.reservation.domain.Reservation
import com.ticketrush.reservation.domain.ReservationRepositoryPort
import com.ticketrush.reservation.domain.SeatHoldFilterPort
import com.ticketrush.reservation.domain.SeatRepositoryPort
import com.ticketrush.reservation.domain.SeatStatus
import com.ticketrush.reservation.infrastructure.SeatHoldFilterRedisAdapter
import com.ticketrush.shared.PhoneHash
import com.ticketrush.support.IntegrationTest
import com.ticketrush.support.공연_하나_저장
import com.ticketrush.support.등급_하나_저장
import com.ticketrush.support.예약_하나_저장
import com.ticketrush.support.홀드된_좌석_하나_저장
import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Clock
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.Test

// 마크 동기화가 Redis를 부르는 동안 DB 연결을 쥐고 있는지 풀에서 직접 본다. Redis 호출 자리에 풀 상태를 읽는 래퍼를 끼운다
@Import(SeatMarkConnectionTest.ProbeConfig::class)
class SeatMarkConnectionTest : IntegrationTest() {
    @Autowired
    lateinit var eventRepository: EventRepositoryPort

    @Autowired
    lateinit var gradeRepository: GradeRepositoryPort

    @Autowired
    lateinit var seatRepository: SeatRepositoryPort

    @Autowired
    lateinit var reservationRepository: ReservationRepositoryPort

    @Autowired
    lateinit var paymentRepository: PaymentRepositoryPort

    @Autowired
    lateinit var claimService: PaymentClaimService

    @Autowired
    lateinit var probe: ProbeSeatHoldFilter

    @Autowired
    lateinit var dataSource: DataSource

    @Autowired
    lateinit var clock: Clock

    @TestConfiguration
    class ProbeConfig {
        @Bean
        @Primary
        fun probeSeatHoldFilter(
            redisTemplate: StringRedisTemplate,
            seatPolicy: SeatPolicyProperties,
            dataSource: DataSource,
        ): ProbeSeatHoldFilter =
            ProbeSeatHoldFilter(SeatHoldFilterRedisAdapter(redisTemplate, seatPolicy), dataSource.unwrap(HikariDataSource::class.java))
    }

    @AfterEach
    fun 감시를_초기화한다() = probe.reset()

    @Test
    fun `결제 확정의 Redis 동기화가 도는 동안 DB 연결을 쥐지 않는다`() {
        // given
        val holds = List(SYNC_COUNT) { 마크가_있는_홀드() }

        // when
        holds.forEach { 확정(it) }

        // then: 스케줄러 같은 다른 곳이 잠깐 쥘 수 있어 최솟값으로 본다
        assertThat(probe.activeAtSync).hasSize(SYNC_COUNT)
        assertThat(probe.activeAtSync.min()).isZero()
    }

    @Test
    fun `Redis가 느려도 풀의 다른 요청을 막지 않는다`() {
        // given: 풀 크기만큼 동시에 확정하고 Redis는 느리다
        val poolSize = probe.pool.maximumPoolSize
        val holds = List(poolSize) { 마크가_있는_홀드() }
        probe.syncDelayMillis = SLOW_REDIS_MILLIS
        probe.entered = CountDownLatch(poolSize)
        val workers = Executors.newFixedThreadPool(poolSize)

        try {
            // when: 모두가 Redis 호출 안에 들어간 뒤에 다른 요청이 연결을 얻으려 한다
            val confirms = holds.map { held -> CompletableFuture.runAsync({ 확정(held) }, workers) }
            assertThat(probe.entered!!.await(10, TimeUnit.SECONDS))
                .withFailMessage("풀이 고갈돼 일부 확정이 Redis 호출까지 가지 못했다")
                .isTrue()
            val other = CompletableFuture.supplyAsync { dataSource.connection.use { it.isValid(1) } }

            // then: 연결을 쥔 채 기다린다면 풀이 비어 있지 않아 다른 요청이 연결을 못 얻는다
            assertThat(other.get(1, TimeUnit.SECONDS)).isTrue()
            confirms.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            workers.shutdownNow()
        }
    }

    @Test
    fun `Redis 동기화가 실패해도 결제 확정은 커밋된다`() {
        // given
        val held = 마크가_있는_홀드()
        probe.failSync = true

        // when
        val result = 확정(held)

        // then
        assertThat(paymentRepository.findByReservationId(held.reservation.id)!!.status).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(result.reservationNo).isNotBlank()
        assertThat(seatRepository.findAllByReservationId(held.reservation.id).single().status).isEqualTo(SeatStatus.SOLD)
    }

    private fun 확정(held: HeldSeat) =
        claimService.applySuccess(
            claimService.claimOrTakeOver(
                held.reservation.id,
                held.reservation.amount,
                "payment-key-${UUID.randomUUID()}",
                UUID.randomUUID().toString(),
                LocalDateTime.now(clock),
            ),
            held.reservation,
            "PG-TX-${UUID.randomUUID()}",
            LocalDateTime.now(clock),
        )

    private fun 마크가_있는_홀드(): HeldSeat {
        val event = eventRepository.공연_하나_저장()
        val grade = gradeRepository.등급_하나_저장(event)
        val phoneHash = PhoneHash(ByteArray(32) { 1 })
        val reservation = reservationRepository.예약_하나_저장(event, phoneHash = phoneHash)
        val seat = seatRepository.홀드된_좌석_하나_저장(event, grade, reservation.id, phoneHash, slotNo = 1)
        assertThat(probe.tryClaim(event.id, listOf(seat.id), reservation.holdToken.toString())).isTrue()
        return HeldSeat(reservation)
    }

    private class HeldSeat(
        val reservation: Reservation,
    )

    // 판매 마크를 늘리는 호출에서 풀의 사용 중 연결 수를 기록하고, 필요하면 느리게 하거나 실패시킨다
    class ProbeSeatHoldFilter(
        private val delegate: SeatHoldFilterPort,
        val pool: HikariDataSource,
    ) : SeatHoldFilterPort by delegate {
        val activeAtSync = CopyOnWriteArrayList<Int>()

        @Volatile
        var syncDelayMillis = 0L

        @Volatile
        var failSync = false

        @Volatile
        var entered: CountDownLatch? = null

        override fun extendForSold(
            eventId: Long,
            seatIds: List<Long>,
            holdToken: String,
        ) {
            activeAtSync += pool.hikariPoolMXBean.activeConnections
            entered?.countDown()
            if (syncDelayMillis > 0) Thread.sleep(syncDelayMillis)
            check(!failSync) { "Redis 장애" }
            delegate.extendForSold(eventId, seatIds, holdToken)
        }

        fun reset() {
            activeAtSync.clear()
            syncDelayMillis = 0L
            failSync = false
            entered = null
        }
    }

    companion object {
        private const val SYNC_COUNT = 10
        private const val SLOW_REDIS_MILLIS = 1500L
    }
}
