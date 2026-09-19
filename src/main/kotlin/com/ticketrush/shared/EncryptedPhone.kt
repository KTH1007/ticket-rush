package com.ticketrush.shared

// AES-256-GCM으로 암호화한 전화번호. 양방향 설계
class EncryptedPhone(
    value: ByteArray,
) {
    private val bytes: ByteArray = value.copyOf()

    val value: ByteArray get() = bytes.copyOf()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncryptedPhone) return false
        return bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = bytes.contentHashCode()
}
