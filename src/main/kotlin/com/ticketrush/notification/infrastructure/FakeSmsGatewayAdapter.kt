package com.ticketrush.notification.infrastructure

import com.ticketrush.notification.domain.NotificationPort
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

// 실제 SMS 벤더 연동 전까지 쓰는 가짜 구현체. 항상 성공하고 로그만 남긴다(FakePaymentGatewayAdapter와 같은 패턴)
@Component
class FakeSmsGatewayAdapter : NotificationPort {
    override fun sendSms(
        phone: String,
        message: String,
    ) {
        logger.info { "[FAKE SMS] to=$phone message=\"$message\"" }
    }
}
