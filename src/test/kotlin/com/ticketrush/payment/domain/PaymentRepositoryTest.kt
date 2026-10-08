package com.ticketrush.payment.domain

import com.ticketrush.event.domain.EventRepositoryPort
import com.ticketrush.reservation.domain.Reservation
import com.ticketrush.reservation.domain.ReservationRepositoryPort
import com.ticketrush.support.IntegrationTest
import com.ticketrush.support.공연_하나_저장
import com.ticketrush.support.예약_하나_저장
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.Test

class PaymentRepositoryTest : IntegrationTest() {
    @Autowired
    lateinit var eventRepository: EventRepositoryPort

    @Autowired
    lateinit var reservationRepository: ReservationRepositoryPort

    @Autowired
    lateinit var paymentRepository: PaymentRepositoryPort

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var clock: Clock

    @PersistenceContext
    lateinit var entityManager: EntityManager

    @Test
    fun `결제를 저장하면 값이 그대로 조회된다`() {
        // given
        val reservation = 예약_하나_저장()

        // when
        val saved = paymentRepository.save(결제(reservation.id))

        // then
        assertThat(saved.reservationId).isEqualTo(reservation.id)
        assertThat(saved.amount).isEqualTo(100_000)
        assertThat(saved.status).isEqualTo(PaymentStatus.PENDING)
    }

    @Test
    fun `reservationId로 결제를 조회한다`() {
        // given
        val reservation = 예약_하나_저장()
        paymentRepository.save(결제(reservation.id))

        // when
        val found = paymentRepository.findByReservationId(reservation.id)

        // then
        assertThat(found).isNotNull
        assertThat(found?.reservationId).isEqualTo(reservation.id)
    }

    @Test
    fun `결제가 없는 예약 id로 조회하면 null을 반환한다`() {
        // when
        val found = paymentRepository.findByReservationId(999_999L)

        // then
        assertThat(found).isNull()
    }

    @Test
    fun `uk_payment_reservation 위반 시(한 예약에 결제 두 건) 예외 발생`() {
        // given
        val reservation = 예약_하나_저장()
        paymentRepository.save(결제(reservation.id))

        // when & then
        assertThatThrownBy { paymentRepository.save(결제(reservation.id)) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
            .hasMessageContaining("uk_payment_reservation")
    }

    @Test
    fun `ck_payment_amount 위반 시 예외 발생`() {
        // given
        val reservation = 예약_하나_저장()

        // when & then
        assertThatThrownBy { paymentRepository.save(결제(reservation.id, amount = -1)) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
            .hasMessageContaining("ck_payment_amount")
    }

    @Test
    fun `ck_payment_success_paid_at 위반 시 예외 발생`() {
        // given
        val reservation = 예약_하나_저장()

        // when & then
        // SUCCESS인데 paid_at이 없는 상태를 DB가 거부하는지 확인
        assertThatThrownBy {
            paymentRepository.save(결제(reservation.id, status = PaymentStatus.SUCCESS, paidAt = null))
        }.isInstanceOf(DataIntegrityViolationException::class.java)
            .hasMessageContaining("ck_payment_success_paid_at")
    }

    @Test
    fun `ck_payment_status 위반 값 저장 시도 시 예외 발생`() {
        // given
        val reservation = 예약_하나_저장()

        // when & then
        // status는 enum이라 Kotlin 타입으로는 잘못된 값을 만들 수 없어 우회 INSERT로 검증
        assertThatThrownBy {
            jdbcTemplate.update(
                """
                INSERT INTO payment (reservation_id, amount, status, created_at, updated_at)
                VALUES (?, 10000, 'INVALID', now(), now())
                """.trimIndent(),
                reservation.id,
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
            .hasMessageContaining("ck_payment_status")
    }

    @Test
    @Transactional
    fun `stale PENDING 결제는 updatedAt이 오래된 순서로 조회된다`() {
        // given: 저장 순서와 updatedAt 순서가 다르게 만든다
        val newest = paymentRepository.save(결제(예약_하나_저장().id, tossPaymentKey = "pk-newest"))
        val oldest = paymentRepository.save(결제(예약_하나_저장().id, tossPaymentKey = "pk-oldest"))
        val middle = paymentRepository.save(결제(예약_하나_저장().id, tossPaymentKey = "pk-middle"))
        updatedAt을_과거로_되돌리기(newest, hours = 1)
        updatedAt을_과거로_되돌리기(oldest, hours = 3)
        updatedAt을_과거로_되돌리기(middle, hours = 2)

        // when
        val stale = paymentRepository.findStalePending(LocalDateTime.now(clock).minusMinutes(30))

        // then
        assertThat(stale.map { it.id }.filter { it in setOf(newest.id, oldest.id, middle.id) })
            .containsExactly(oldest.id, middle.id, newest.id)
    }

    // 표시된 행을 계속 조회하면 회수 스케줄러가 틱마다 Toss를 다시 부르고 같은 알림을 반복한다. 표시가 DB에 있어 재시작해도 유지된다
    @Test
    @Transactional
    fun `환불 필요로 표시된 PENDING 결제는 stale 조회에서 빠지고 건수에 센다`() {
        // given
        val unmarked = paymentRepository.save(결제(예약_하나_저장().id, tossPaymentKey = "pk-unmarked"))
        val marked = paymentRepository.save(결제(예약_하나_저장().id, tossPaymentKey = "pk-marked"))
        marked.markRefundRequired(LocalDateTime.now(clock))
        paymentRepository.save(marked)
        listOf(unmarked, marked).forEach { updatedAt을_과거로_되돌리기(it, hours = 1) }
        val before = paymentRepository.countRefundRequired()

        // when
        val ids = paymentRepository.findStalePending(LocalDateTime.now(clock).minusMinutes(30)).map { it.id }

        // then
        assertThat(ids).contains(unmarked.id).doesNotContain(marked.id)
        assertThat(before).isGreaterThanOrEqualTo(1)
    }

    @Test
    @Transactional
    fun `환불 필요 건수는 표시됐지만 더 이상 PENDING이 아닌 결제를 세지 않는다`() {
        val resolved = paymentRepository.save(결제(예약_하나_저장().id, tossPaymentKey = "pk-resolved"))
        resolved.markRefundRequired(LocalDateTime.now(clock))
        val before = paymentRepository.countRefundRequired()

        // 사람이 Toss 관리자에서 환불한 뒤 상태를 정리하면 건수에서 빠진다
        resolved.markFailed()
        paymentRepository.save(resolved)

        assertThat(paymentRepository.countRefundRequired()).isEqualTo(before - 1)
    }

    @Test
    @Transactional
    fun `환불 시도 횟수는 0으로 저장되고 기록하면 늘어난 값이 DB에 저장된다`() {
        // given
        val saved = 취소된_결제()
        assertThat(saved.refundAttemptCount).isEqualTo(0)

        // when
        saved.recordRefundAttempt()
        paymentRepository.save(saved)
        entityManager.flush()
        entityManager.clear()

        // then
        assertThat(requireNotNull(paymentRepository.findByReservationId(saved.reservationId)).refundAttemptCount).isEqualTo(1)
    }

    @Test
    fun `환불 시도 횟수 컬럼은 값을 안 넣으면 DB 기본값 0이 된다`() {
        val reservation = 예약_하나_저장()

        jdbcTemplate.update(
            "INSERT INTO payment (reservation_id, amount, status, created_at, updated_at) VALUES (?, 10000, 'PENDING', now(), now())",
            reservation.id,
        )

        val count =
            jdbcTemplate.queryForObject(
                "SELECT refund_attempt_count FROM payment WHERE reservation_id = ?",
                Int::class.java,
                reservation.id,
            )
        assertThat(count).isEqualTo(0)
    }

    @Test
    fun `승인 거절 횟수 컬럼은 값을 안 넣으면 DB 기본값 0이 되고 음수는 CHECK 제약이 막는다`() {
        val reservation = 예약_하나_저장()
        jdbcTemplate.update(
            "INSERT INTO payment (reservation_id, amount, status, created_at, updated_at) VALUES (?, 10000, 'PENDING', now(), now())",
            reservation.id,
        )

        val count =
            jdbcTemplate.queryForObject(
                "SELECT charge_attempt_count FROM payment WHERE reservation_id = ?",
                Int::class.java,
                reservation.id,
            )

        assertThat(count).isEqualTo(0)
        assertThatThrownBy {
            jdbcTemplate.update("UPDATE payment SET charge_attempt_count = -1 WHERE reservation_id = ?", reservation.id)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
            .hasMessageContaining("ck_payment_charge_attempt_count")
    }

    @Test
    fun `환불 시도 횟수가 음수면 CHECK 제약이 막는다`() {
        val reservation = 예약_하나_저장()

        assertThatThrownBy {
            jdbcTemplate.update(
                """
                INSERT INTO payment (reservation_id, amount, status, refund_attempt_count, created_at, updated_at)
                VALUES (?, 10000, 'CANCELED', -1, now(), now())
                """.trimIndent(),
                reservation.id,
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
            .hasMessageContaining("ck_payment_refund_attempt_count")
    }

    @Test
    @Transactional
    fun `stale CANCELED 결제는 updatedAt이 오래된 순서로 조회된다`() {
        // given: 저장 순서와 updatedAt 순서가 다르게 만든다
        val newest = 취소된_결제()
        val oldest = 취소된_결제()
        val middle = 취소된_결제()
        updatedAt을_과거로_되돌리기(newest, hours = 1)
        updatedAt을_과거로_되돌리기(oldest, hours = 3)
        updatedAt을_과거로_되돌리기(middle, hours = 2)

        // when
        val stale = paymentRepository.findStaleCanceled(LocalDateTime.now(clock).minusMinutes(30), maxAttempts = 5)

        // then
        assertThat(stale.map { it.id }.filter { it in setOf(newest.id, oldest.id, middle.id) })
            .containsExactly(oldest.id, middle.id, newest.id)
    }

    @Test
    @Transactional
    fun `환불 시도 횟수가 한도에 도달한 CANCELED 결제는 조회되지 않는다`() {
        // given
        val underLimit = 취소된_결제(refundAttemptCount = 4)
        val atLimit = 취소된_결제(refundAttemptCount = 5)
        updatedAt을_과거로_되돌리기(underLimit, hours = 1)
        updatedAt을_과거로_되돌리기(atLimit, hours = 1)

        // when
        val ids = paymentRepository.findStaleCanceled(LocalDateTime.now(clock).minusMinutes(30), maxAttempts = 5).map { it.id }

        // then
        assertThat(ids).contains(underLimit.id).doesNotContain(atLimit.id)
    }

    @Test
    @Transactional
    fun `최근에 취소됐거나 CANCELED가 아닌 결제는 stale 조회에서 빠진다`() {
        // given
        val staleCanceled = 취소된_결제()
        val recentCanceled = 취소된_결제()
        val refunded = paymentRepository.save(결제(예약_하나_저장().id, status = PaymentStatus.REFUNDED, pgTransactionId = "pk-refunded"))
        val pending = paymentRepository.save(결제(예약_하나_저장().id, tossPaymentKey = "pk-pending"))
        listOf(staleCanceled, refunded, pending).forEach { updatedAt을_과거로_되돌리기(it, hours = 1) }

        // when
        val ids = paymentRepository.findStaleCanceled(LocalDateTime.now(clock).minusMinutes(30), maxAttempts = 5).map { it.id }

        // then
        assertThat(ids).contains(staleCanceled.id).doesNotContain(recentCanceled.id, refunded.id, pending.id)
    }

    // 감사 필드는 JPA flush 때만 갱신되므로 JDBC로 시간을 되돌린다
    private fun updatedAt을_과거로_되돌리기(
        payment: Payment,
        hours: Int,
    ) {
        entityManager.flush()
        jdbcTemplate.update("UPDATE payment SET updated_at = updated_at - make_interval(hours => ?) WHERE id = ?", hours, payment.id)
        entityManager.clear()
    }

    private fun 예약_하나_저장(): Reservation {
        val event = eventRepository.공연_하나_저장()
        return reservationRepository.예약_하나_저장(event)
    }

    private fun 결제(
        reservationId: Long,
        amount: Int = 100_000,
        status: PaymentStatus = PaymentStatus.PENDING,
        paidAt: LocalDateTime? = null,
        tossPaymentKey: String? = null,
        pgTransactionId: String? = null,
        refundAttemptCount: Int = 0,
    ): Payment =
        Payment(
            reservationId = reservationId,
            amount = amount,
            status = status,
            paidAt = paidAt,
            tossPaymentKey = tossPaymentKey,
            pgTransactionId = pgTransactionId,
            refundAttemptCount = refundAttemptCount,
        )

    private fun 취소된_결제(refundAttemptCount: Int = 0): Payment =
        paymentRepository.save(
            결제(
                예약_하나_저장().id,
                status = PaymentStatus.CANCELED,
                pgTransactionId = "pk-${UUID.randomUUID()}",
                refundAttemptCount = refundAttemptCount,
            ),
        )
}
