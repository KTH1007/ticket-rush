package com.ticketrush.shared.exception

import org.assertj.core.api.Assertions.assertThat
import org.hibernate.exception.JDBCConnectionException
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.CannotCreateTransactionException
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import kotlin.test.Test

class DatabaseFailuresTest {
    @Test
    fun `트랜잭션을 여는 중 풀에서 연결을 못 얻으면 연결 불가다`() {
        // given
        val e = CannotCreateTransactionException("Could not open JPA EntityManager for transaction", poolTimeout())

        // when & then
        assertThat(DatabaseFailures.isConnectionUnavailable(e)).isTrue()
    }

    @Test
    fun `트랜잭션 밖의 조회에서 연결을 못 얻어도 연결 불가다`() {
        // given
        val e = DataAccessResourceFailureException("could not execute query", poolTimeout())

        // when & then
        assertThat(DatabaseFailures.isConnectionUnavailable(e)).isTrue()
    }

    @Test
    fun `연결과 무관한 DB 오류와 일반 예외는 연결 불가가 아니다`() {
        assertThat(DatabaseFailures.isConnectionUnavailable(DataIntegrityViolationException("중복 키"))).isFalse()
        assertThat(DatabaseFailures.isConnectionUnavailable(RuntimeException(SQLException("syntax error")))).isFalse()
        assertThat(DatabaseFailures.isConnectionUnavailable(IllegalStateException("boom"))).isFalse()
    }

    @Test
    fun `요약은 가장 안쪽 원인의 앞부분만 주고 풀 상태가 남는다`() {
        // given
        val e = CannotCreateTransactionException("바깥", poolTimeout())

        // when
        val summary = DatabaseFailures.summary(e)

        // then
        assertThat(summary).contains("Connection is not available", "active=20", "waiting=200")
        assertThat(summary.length).isLessThanOrEqualTo(160)
    }

    private fun poolTimeout() =
        JDBCConnectionException(
            "Unable to acquire JDBC Connection",
            SQLTransientConnectionException(
                "HikariPool-1 - Connection is not available, request timed out after 3037ms " +
                    "(total=20, active=20, idle=0, waiting=200)",
            ),
        )
}
