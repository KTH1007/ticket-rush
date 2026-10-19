package com.ticketrush.payment

import com.ticketrush.event.domain.EventRepositoryPort
import com.ticketrush.payment.domain.Payment
import com.ticketrush.payment.domain.PaymentHistoryRepositoryPort
import com.ticketrush.payment.domain.PaymentRepositoryPort
import com.ticketrush.payment.domain.PaymentStatus
import com.ticketrush.reservation.domain.ReservationRepositoryPort
import com.ticketrush.support.IntegrationTest
import com.ticketrush.support.공연_하나_저장
import com.ticketrush.support.예약_하나_저장
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.Container.ExecResult
import org.testcontainers.utility.MountableFile
import java.nio.file.Path
import java.time.Clock
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.Test

// 운영자가 실제로 실행하는 SQL 파일을 psql로 그대로 돌린다. psql은 별도 연결이라 롤백되는 테스트를 쓸 수 없어 커밋하고,
// 다른 테스트의 재시도 스케줄러가 남은 행을 집어가지 않게 끝나고 지운다
class PaymentRefundManualResolutionTest : IntegrationTest() {
    @Autowired
    lateinit var eventRepository: EventRepositoryPort

    @Autowired
    lateinit var reservationRepository: ReservationRepositoryPort

    @Autowired
    lateinit var paymentRepository: PaymentRepositoryPort

    @Autowired
    lateinit var paymentHistoryRepository: PaymentHistoryRepositoryPort

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var clock: Clock

    private val createdPaymentIds = mutableListOf<Long>()

    @AfterEach
    fun cleanUp() {
        createdPaymentIds.forEach {
            jdbcTemplate.update("DELETE FROM payment_history WHERE payment_id = ?", it)
            jdbcTemplate.update("DELETE FROM payment WHERE id = ?", it)
        }
        createdPaymentIds.clear()
    }

    @Test
    fun `환불 필요 건을 정리하면 FAILED가 되고 이력이 남고 미처리 건수에서 빠진다`() {
        val payment = 환불_필요_결제()
        val before = paymentRepository.countRefundRequired()

        val result = psql("resolve-refund-required.sql", "payment_id" to payment.id, "note" to "Toss 콘솔에서 환불함 (O'Brien 확인)")

        assertThat(result.exitCode).isZero()
        assertThat(result.stdout.trim()).isEqualTo("1")
        val resolved = 다시_읽기(payment)
        assertThat(resolved.status).isEqualTo(PaymentStatus.FAILED)
        assertThat(resolved.version).isEqualTo(payment.version + 1)
        val history = paymentHistoryRepository.findAllByPaymentId(payment.id).single()
        assertThat(history.fromStatus).isEqualTo(PaymentStatus.PENDING)
        assertThat(history.toStatus).isEqualTo(PaymentStatus.FAILED)
        assertThat(history.reason).isEqualTo("운영자 환불 처리 완료: Toss 콘솔에서 환불함 (O'Brien 확인)")
        assertThat(paymentRepository.countRefundRequired()).isEqualTo(before - 1)
    }

    // 표시가 남아 있어야 같은 예약의 새 승인 요청이 계속 충돌로 막힌다
    @Test
    fun `환불 필요 건을 정리해도 환불 필요 표시는 남는다`() {
        val payment = 환불_필요_결제()

        psql("resolve-refund-required.sql", "payment_id" to payment.id, "note" to "처리")

        assertThat(다시_읽기(payment).refundRequiredAt).isNotNull()
    }

    @Test
    fun `환불 필요로 표시되지 않은 PENDING 결제는 바뀌지 않는다`() {
        val payment = 결제(PaymentStatus.PENDING)

        val result = psql("resolve-refund-required.sql", "payment_id" to payment.id, "note" to "잘못 입력")

        assertThat(result.stdout.trim()).isEqualTo("0")
        assertThat(다시_읽기(payment).status).isEqualTo(PaymentStatus.PENDING)
        assertThat(paymentHistoryRepository.findAllByPaymentId(payment.id)).isEmpty()
    }

    @Test
    fun `이미 정리한 환불 필요 건을 다시 정리하면 아무것도 바뀌지 않는다`() {
        val payment = 환불_필요_결제()
        psql("resolve-refund-required.sql", "payment_id" to payment.id, "note" to "처음")

        val again = psql("resolve-refund-required.sql", "payment_id" to payment.id, "note" to "다시")

        assertThat(again.stdout.trim()).isEqualTo("0")
        assertThat(paymentHistoryRepository.findAllByPaymentId(payment.id)).hasSize(1)
        assertThat(다시_읽기(payment).version).isEqualTo(payment.version + 1)
    }

    @Test
    fun `환불 시도를 소진한 건을 정리하면 REFUNDED가 되고 이력이 남고 소진 건수에서 빠진다`() {
        val payment = 결제(PaymentStatus.CANCELED, refundAttemptCount = MAX_ATTEMPTS)
        val before = paymentRepository.countExhaustedRefunds(MAX_ATTEMPTS)

        val result =
            psql(
                "resolve-refund-exhausted.sql",
                "payment_id" to payment.id,
                "max_attempts" to MAX_ATTEMPTS,
                "note" to "Toss 콘솔에서 환불함",
            )

        assertThat(result.exitCode).isZero()
        assertThat(result.stdout.trim()).isEqualTo("1")
        assertThat(다시_읽기(payment).status).isEqualTo(PaymentStatus.REFUNDED)
        val history = paymentHistoryRepository.findAllByPaymentId(payment.id).single()
        assertThat(history.fromStatus).isEqualTo(PaymentStatus.CANCELED)
        assertThat(history.toStatus).isEqualTo(PaymentStatus.REFUNDED)
        assertThat(history.reason).isEqualTo("운영자 수동 환불 완료: Toss 콘솔에서 환불함")
        assertThat(paymentRepository.countExhaustedRefunds(MAX_ATTEMPTS)).isEqualTo(before - 1)
    }

    // 재시도가 아직 남은 건을 사람이 닫으면 자동 재시도가 끊긴다. 소진한 건만 닫는다
    @Test
    fun `환불 시도가 남은 CANCELED와 다른 상태의 결제는 바뀌지 않는다`() {
        val retrying = 결제(PaymentStatus.CANCELED, refundAttemptCount = MAX_ATTEMPTS - 1)
        val success = 결제(PaymentStatus.SUCCESS)

        listOf(retrying, success).forEach {
            val result =
                psql("resolve-refund-exhausted.sql", "payment_id" to it.id, "max_attempts" to MAX_ATTEMPTS, "note" to "잘못 입력")
            assertThat(result.stdout.trim()).isEqualTo("0")
        }

        assertThat(다시_읽기(retrying).status).isEqualTo(PaymentStatus.CANCELED)
        assertThat(다시_읽기(success).status).isEqualTo(PaymentStatus.SUCCESS)
        assertThat(paymentHistoryRepository.findAllByPaymentId(retrying.id)).isEmpty()
    }

    @Test
    fun `메모를 빠뜨리면 스크립트가 멈추고 아무것도 바뀌지 않는다`() {
        val payment = 환불_필요_결제()

        val result = psql("resolve-refund-required.sql", "payment_id" to payment.id)

        assertThat(result.exitCode).isNotZero()
        assertThat(다시_읽기(payment).status).isEqualTo(PaymentStatus.PENDING)
        assertThat(paymentHistoryRepository.findAllByPaymentId(payment.id)).isEmpty()
    }

    @Test
    fun `긴 메모는 이력 사유 길이에 맞게 잘려 저장된다`() {
        val payment = 환불_필요_결제()

        val result = psql("resolve-refund-required.sql", "payment_id" to payment.id, "note" to "가".repeat(300))

        assertThat(result.exitCode).isZero()
        assertThat(paymentHistoryRepository.findAllByPaymentId(payment.id).single().reason).hasSize(REASON_MAX_LENGTH)
    }

    @Test
    fun `조회 스크립트는 처리할 건과 마지막 이력 사유를 보여 준다`() {
        val required = 환불_필요_결제()
        val exhausted = 결제(PaymentStatus.CANCELED, refundAttemptCount = MAX_ATTEMPTS)
        jdbcTemplate.update(
            "INSERT INTO payment_history (payment_id, from_status, to_status, reason, created_at) " +
                "VALUES (?, 'CANCELED', 'CANCELED', ?, now())",
            exhausted.id,
            "환불 실패 5/5: 거절",
        )

        val requiredList = psql("list-refund-required.sql").stdout
        val exhaustedList = psql("list-refund-exhausted.sql", "max_attempts" to MAX_ATTEMPTS).stdout

        assertThat(requiredList.lines()).anyMatch { it.startsWith("${required.id}|") }
        assertThat(exhaustedList.lines()).anyMatch { it.startsWith("${exhausted.id}|") && it.endsWith("|환불 실패 5/5: 거절") }
    }

    private fun psql(
        script: String,
        vararg variables: Pair<String, Any>,
    ): ExecResult {
        val remote = "/tmp/$script"
        postgres.copyFileToContainer(MountableFile.forHostPath(Path.of("scripts/refund/$script")), remote)
        val command = mutableListOf("psql", "-U", postgres.username, "-d", postgres.databaseName, "-v", "ON_ERROR_STOP=1", "-t", "-A")
        variables.forEach { (name, value) -> command += listOf("-v", "$name=$value") }
        command += listOf("-f", remote)
        return postgres.execInContainer(*command.toTypedArray())
    }

    private fun 환불_필요_결제(): Payment {
        val payment = 결제(PaymentStatus.PENDING)
        payment.markRefundRequired(LocalDateTime.now(clock))
        return paymentRepository.save(payment)
    }

    private fun 결제(
        status: PaymentStatus,
        refundAttemptCount: Int = 0,
    ): Payment {
        val reservation = reservationRepository.예약_하나_저장(eventRepository.공연_하나_저장())
        val payment =
            paymentRepository.save(
                Payment(
                    reservationId = reservation.id,
                    amount = 100_000,
                    status = status,
                    paidAt = LocalDateTime.now(clock).takeIf { status == PaymentStatus.SUCCESS },
                    pgTransactionId = "pk-${UUID.randomUUID()}".takeIf { status != PaymentStatus.PENDING },
                    tossPaymentKey = "pk-${UUID.randomUUID()}".takeIf { status == PaymentStatus.PENDING },
                    refundAttemptCount = refundAttemptCount,
                ),
            )
        createdPaymentIds += payment.id
        return payment
    }

    private fun 다시_읽기(payment: Payment): Payment = requireNotNull(paymentRepository.findByReservationId(payment.reservationId))

    private companion object {
        const val MAX_ATTEMPTS = 5

        // payment_history.reason 컬럼 길이
        const val REASON_MAX_LENGTH = 200
    }
}
