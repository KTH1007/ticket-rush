package com.ticketrush.shared.exception

import java.sql.SQLTransientConnectionException

private const val SUMMARY_LENGTH = 160

// 연결 풀에서 연결을 얻지 못한 경우(풀 고갈, DB 연결 실패)를 가른다. 기다리면 풀릴 수 있다.
// 스프링이 호출 경로에 따라 감싸는 예외가 달라서(CannotCreateTransactionException, DataAccessResourceFailureException 등) 원인 사슬에서 JDBC 표준 예외를 찾는다
internal object DatabaseFailures {
    fun isConnectionUnavailable(e: Throwable): Boolean = e.causeChain().any { it is SQLTransientConnectionException }

    fun summary(e: Throwable): String =
        e
            .causeChain()
            .last()
            .message
            .orEmpty()
            .take(SUMMARY_LENGTH)
}
