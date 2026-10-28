package com.ticketrush.shared.exception

import io.lettuce.core.RedisCommandExecutionException
import io.lettuce.core.RedisCommandTimeoutException
import io.lettuce.core.RedisConnectionException
import org.springframework.data.redis.RedisConnectionFailureException

private const val MAX_CAUSE_DEPTH = 10
private const val SUMMARY_LENGTH = 120

// 다시 시도하면 풀릴 수 있는 Redis 장애인지 가른다. 스크립트 오류나 WRONGTYPE 같은 버그는 해당하지 않는다.
// 어댑터가 아니라 예외 처리기에서 가르는 이유: 어댑터에서 도메인 예외로 바꾸면 DataAccessException을 잡아 삼키는 기존 베스트 에포트 코드의 동작이 달라진다
internal object RedisFailures {
    // 서버가 돌려주는 오류 접두어. OOM은 maxmemory, LOADING은 데이터 적재 중, BUSY는 긴 스크립트 실행 중, MISCONF는 스냅샷 저장 실패로 쓰기 중단
    private val TRANSIENT_ERROR_PREFIXES = listOf("OOM ", "LOADING ", "BUSY ", "MISCONF ")

    fun isTransient(e: Throwable): Boolean = causes(e).any(::isTransientCause)

    fun summary(e: Throwable): String =
        causes(e)
            .last()
            .message
            .orEmpty()
            .take(SUMMARY_LENGTH)

    private fun isTransientCause(cause: Throwable): Boolean =
        when (cause) {
            is RedisConnectionFailureException, is RedisConnectionException, is RedisCommandTimeoutException -> true
            is RedisCommandExecutionException -> TRANSIENT_ERROR_PREFIXES.any { cause.message.orEmpty().startsWith(it) }
            else -> false
        }

    private fun causes(e: Throwable): List<Throwable> =
        generateSequence(e) { it.cause?.takeIf { next -> next !== it } }.take(MAX_CAUSE_DEPTH).toList()
}
