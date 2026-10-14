package com.ticketrush.support

import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.core.RedisCallback
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript

// Redis 재시작이나 장애 조치로 스크립트 캐시가 빈 상태를 흉내 낸다
fun StringRedisTemplate.스크립트_캐시_비우기() {
    execute(RedisCallback { it.scriptingCommands().scriptFlush() })
}

fun StringRedisTemplate.스크립트가_캐시돼_있다(resourcePath: String): Boolean {
    val sha1 = DefaultRedisScript<Any>().apply { setLocation(ClassPathResource(resourcePath)) }.sha1
    return execute(RedisCallback { it.scriptingCommands().scriptExists(sha1).first() }) == true
}
