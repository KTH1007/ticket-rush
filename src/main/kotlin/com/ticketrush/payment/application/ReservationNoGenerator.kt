package com.ticketrush.payment.application

import org.springframework.stereotype.Component
import java.security.SecureRandom

// 예매번호. 결제 확정 시 발급하고 이후 예매번호+전화번호 조합으로 조회 인증
@Component
class ReservationNoGenerator {
    private val random = SecureRandom()

    fun generate(): String = (1..LENGTH).map { CHARS[random.nextInt(CHARS.length)] }.joinToString("")

    companion object {
        // 사람이 직접 옮겨 적는 값이라 헷갈리는 문자쌍(0/O, 1/I)은 뺀다
        private const val CHARS = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        private const val LENGTH = 12
    }
}
