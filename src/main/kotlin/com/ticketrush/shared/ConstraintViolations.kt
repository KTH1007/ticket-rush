package com.ticketrush.shared

import org.postgresql.util.PSQLException

private const val UNIQUE_VIOLATION_SQL_STATE = "23505"

// 메시지 문자열은 서버 언어와 드라이버 버전에 따라 달라져 SQLState와 구조화된 제약 이름 필드로 구분한다
fun Throwable.isUniqueViolation(constraint: String): Boolean =
    generateSequence(this) { it.cause }
        .filterIsInstance<PSQLException>()
        .any { it.sqlState == UNIQUE_VIOLATION_SQL_STATE && it.serverErrorMessage?.constraint == constraint }
