package com.ticketrush.support

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

// 홀드 5분 만료 같은 시간 의존 로직을 재현하려면 시각을 고정하고 진행시켜야 한다
class TestClock(
    private var current: Instant = Instant.parse("2026-08-15T20:00:00Z"),
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {
    override fun instant(): Instant = current

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = TestClock(current, zone)

    fun advanceSeconds(seconds: Long) {
        current = current.plusSeconds(seconds)
    }

    fun advanceMinutes(minutes: Long) {
        advanceSeconds(minutes * 60)
    }
}
