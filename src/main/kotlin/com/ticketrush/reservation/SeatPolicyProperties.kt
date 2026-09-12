package com.ticketrush.reservation

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "ticket-rush.seat")
data class SeatPolicyProperties(
    val holdTtl: Duration,
    val maxPerPhone: Int,
)
