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
        logger.info { "[FAKE SMS] to=${mask(phone)} message=\"$message\"" }
    }

    // 로그에 전화번호 원문이 남지 않도록 가운데 자리를 가린다. 앞 3자리+마지막 4자리만 남김(예: 010****9999)
    private fun mask(phone: String): String =
        if (phone.length <= 7) "*".repeat(phone.length) else phone.take(3) + "*".repeat(phone.length - 7) + phone.takeLast(4)
}
