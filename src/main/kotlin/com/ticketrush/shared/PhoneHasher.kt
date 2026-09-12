package com.ticketrush.shared

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// 전화번호 원문을 저장하지 않기 위해, 서버만 아는 키로 HMAC-SHA256을 계산
@Component
class PhoneHasher(
    @Value("\${ticket-rush.security.phone-hmac-key}") private val key: String,
) {
    // 매 호출마다 새로 만듦
    fun hash(phoneNumber: String): PhoneHash {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(key.toByteArray(), ALGORITHM))
        return PhoneHash(mac.doFinal(phoneNumber.toByteArray()))
    }

    companion object {
        private const val ALGORITHM = "HmacSHA256"
    }
}
