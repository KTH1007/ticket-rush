package com.ticketrush.reservation.application

import com.ticketrush.event.domain.Event
import com.ticketrush.event.domain.EventRepositoryPort
import com.ticketrush.reservation.SeatPolicyProperties
import com.ticketrush.reservation.domain.Grade
import com.ticketrush.reservation.domain.GradeRepositoryPort
import com.ticketrush.reservation.domain.ReservationLimitExceededException
import com.ticketrush.reservation.domain.ReservationRepositoryPort
import com.ticketrush.reservation.domain.Seat
import com.ticketrush.reservation.domain.SeatHoldFilterPort
import com.ticketrush.reservation.domain.SeatRepositoryPort
import com.ticketrush.reservation.domain.SeatSelection
import com.ticketrush.shared.EncryptedPhone
import com.ticketrush.shared.PhoneHash
import com.ticketrush.support.IntegrationTest
import com.ticketrush.support.공연_하나_저장
import com.ticketrush.support.등급_하나_저장
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.LocalDateTime
import kotlin.test.Test

// 앱 검증을 일부러 비켜 가게 만들어, 번역이 DB 위반에서 나온 것임을 보장한다
class ReservationConstraintTranslationTest : IntegrationTest() {
    @Autowired
    lateinit var eventRepository: EventRepositoryPort

    @Autowired
    lateinit var gradeRepository: GradeRepositoryPort

    @Autowired
    lateinit var seatRepository: SeatRepositoryPort

    @Autowired
    lateinit var reservationRepository: ReservationRepositoryPort

    @Autowired
    lateinit var reservationCommandService: ReservationCommandService

    @Autowired
    lateinit var seatHoldFilter: SeatHoldFilterPort

    @Autowired
    lateinit var clock: Clock

    @Autowired
    lateinit var seatPolicy: SeatPolicyProperties

    @Autowired
    lateinit var transactionTemplate: TransactionTemplate

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    private val phoneHash = PhoneHash(ByteArray(32) { 1 })
    private val encryptedPhone = EncryptedPhone(ByteArray(28) { 0 })

    @Test
    fun `이미 쓴 slot을 다시 잡다 ux_seat_slot에 걸리면 한도 초과로 번역한다`() {
        // given
        val (event, seats) = 공연과_좌석_두개_준비()
        reservationCommandService.holdSeats(event.id, SeatSelection(listOf(seats[0].id)), phoneHash, encryptedPhone)
        val service = 서비스(seatRepository.사용중_slot을_비워서_돌려주는())

        // when & then
        assertThatThrownBy {
            service.holdSeats(event.id, SeatSelection(listOf(seats[1].id)), phoneHash, encryptedPhone)
        }.isInstanceOf(ReservationLimitExceededException::class.java)
    }

    @Test
    fun `ux_seat_slot이 아닌 제약 위반은 번역하지 않고 그대로 던진다`() {
        // given
        val (event, seats) = 공연과_좌석_두개_준비()
        val service = 서비스(seatRepository.slot_번호를_범위_밖으로_바꾸는())

        // when & then
        assertThatThrownBy {
            service.holdSeats(event.id, SeatSelection(listOf(seats[0].id)), phoneHash, encryptedPhone)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
            .hasMessageContaining("ck_seat_slot_no")
    }

    @Test
    fun `번역에 쓰는 제약 이름이 DB에 실제로 있다`() {
        val count = jdbcTemplate.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname = ?", Long::class.java, SEAT_SLOT_INDEX)

        assertThat(count).isEqualTo(1L)
    }

    private fun 서비스(seatRepository: SeatRepositoryPort): ReservationCommandService =
        ReservationCommandService(
            seatRepository,
            reservationRepository,
            gradeRepository,
            seatHoldFilter,
            clock,
            seatPolicy,
            transactionTemplate,
        )

    private fun 공연과_좌석_두개_준비(): Pair<Event, List<Seat>> {
        val event = eventRepository.공연_하나_저장()
        val grade = gradeRepository.등급_하나_저장(event)
        return event to listOf(좌석(event, grade, seatNo = 1), 좌석(event, grade, seatNo = 2)).map { seatRepository.save(it) }
    }

    private fun 좌석(
        event: Event,
        grade: Grade,
        seatNo: Short,
    ): Seat = Seat(eventId = event.id, gradeId = grade.id, section = "A", rowLabel = "1", seatNo = seatNo, ordinal = seatNo.toInt())

    // 동시 요청이 겹쳐 앱의 slot 검증을 같이 통과한 상황을 만든다
    private fun SeatRepositoryPort.사용중_slot을_비워서_돌려주는(): SeatRepositoryPort {
        val delegate = this
        return object : SeatRepositoryPort by delegate {
            override fun findHeldOrSoldSlotNos(
                eventId: Long,
                phoneHash: PhoneHash,
            ): List<Short> = emptyList()
        }
    }

    private fun SeatRepositoryPort.slot_번호를_범위_밖으로_바꾸는(): SeatRepositoryPort {
        val delegate = this
        return object : SeatRepositoryPort by delegate {
            override fun holdIfAvailable(
                eventId: Long,
                seatId: Long,
                reservationId: Long,
                phoneHash: PhoneHash,
                slotNo: Short,
                holdExpiresAt: LocalDateTime,
            ): Boolean = delegate.holdIfAvailable(eventId, seatId, reservationId, phoneHash, OUT_OF_RANGE_SLOT, holdExpiresAt)
        }
    }

    companion object {
        private const val OUT_OF_RANGE_SLOT: Short = 3
    }
}
