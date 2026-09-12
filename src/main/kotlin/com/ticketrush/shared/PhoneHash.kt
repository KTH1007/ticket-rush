package com.ticketrush.shared

// HMAC-SHA256(전화번호, 서버 키)로 만든 32바이트 다이제스트를 감싼다. 전화번호
// 원문은 저장하지 않고 이 해시만 저장한다.
class PhoneHash(
    val value: ByteArray,
) {
    init {
        require(value.size == HMAC_SHA256_SIZE) { "전화번호 해시는 ${HMAC_SHA256_SIZE} 바이트여야 합니다" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PhoneHash) return false
        return value.contentEquals(other.value)
    }

    override fun hashCode(): Int = value.contentHashCode()

    companion object {
        private const val HMAC_SHA256_SIZE = 32
    }
}
