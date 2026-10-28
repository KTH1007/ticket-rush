package com.ticketrush.shared.exception

import io.lettuce.core.RedisCommandExecutionException
import io.lettuce.core.RedisCommandTimeoutException
import io.lettuce.core.RedisConnectionException
import org.assertj.core.api.Assertions.assertThat
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.RedisSystemException
import kotlin.test.Test

class RedisFailuresTest {
    @Test
    fun `메모리 한도, 적재 중, 스크립트 실행 중, 스냅샷 저장 실패는 일시적이다`() {
        listOf(
            "OOM command not allowed",
            "LOADING Redis is loading the dataset",
            "BUSY Redis is busy running a script",
            "MISCONF Redis is configured to save RDB",
        ).forEach { message ->
            // given
            val e = RedisSystemException("Error in execution", RedisCommandExecutionException(message))

            // when & then
            assertThat(RedisFailures.isTransient(e)).`as`(message).isTrue()
        }
    }

    @Test
    fun `연결 실패와 명령 타임아웃은 일시적이다`() {
        assertThat(RedisFailures.isTransient(RedisConnectionFailureException("끊김"))).isTrue()
        assertThat(RedisFailures.isTransient(RuntimeException(RedisConnectionException("연결 못함")))).isTrue()
        assertThat(RedisFailures.isTransient(RedisSystemException("timeout", RedisCommandTimeoutException("시간 초과")))).isTrue()
    }

    @Test
    fun `원인이 여러 겹 감싸여도 일시적인 원인을 찾는다`() {
        // given
        val cause = RedisCommandExecutionException("OOM command not allowed")
        val e = IllegalStateException("바깥", RedisSystemException("Error in execution", cause))

        // when & then
        assertThat(RedisFailures.isTransient(e)).isTrue()
    }

    @Test
    fun `WRONGTYPE이나 스크립트 오류나 Redis와 무관한 예외는 일시적이지 않다`() {
        assertThat(RedisFailures.isTransient(redisError("WRONGTYPE Operation"))).isFalse()
        assertThat(RedisFailures.isTransient(redisError("ERR Error compiling script"))).isFalse()
        assertThat(RedisFailures.isTransient(IllegalStateException("boom"))).isFalse()
    }

    @Test
    fun `요약은 가장 안쪽 원인의 앞부분만 준다`() {
        // given
        val e = redisError("OOM command not allowed when used memory > 'maxmemory'. " + "x".repeat(300))

        // when
        val summary = RedisFailures.summary(e)

        // then
        assertThat(summary).startsWith("OOM command not allowed")
        assertThat(summary.length).isLessThanOrEqualTo(120)
    }

    private fun redisError(message: String) = RedisSystemException("Error in execution", RedisCommandExecutionException(message))
}
