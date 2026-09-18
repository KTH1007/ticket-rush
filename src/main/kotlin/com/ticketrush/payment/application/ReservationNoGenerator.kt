package com.ticketrush.payment.application

import org.springframework.stereotype.Component
import java.security.SecureRandom

// 예매번호. 결제 확정 시 발급하고 이후 예매번호+전화번호 조합으로 조회 인증
@Component
class ReservationNoGenerator {
    private val random = SecureRandom()

    fun generate(): String = (1..LENGTH).map { CHARS[random.nextInt(CHARS.length)] }.joinToString("")

    companion object {
        private const val CHARS = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        private const val LENGTH = 12
    }
}
