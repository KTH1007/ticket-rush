package com.ticketrush.shared

// HMAC-SHA256(전화번호, 서버 키)로 만든 32바이트 다이제스트를 감싼다. 전화번호
// 원문은 저장하지 않고 이 해시만 저장한다.
class PhoneHash(
    value: ByteArray,
) {
    // 생성자로 받은 배열을 그대로 들고 있으면 호출부가 그 배열을 나중에 mutate했을 때
    // "내용 기준 동등성"이라는 이 타입의 존재 이유 자체가 깨진다. 32바이트라 복사 비용은 무시할 수준.
    private val bytes: ByteArray = value.copyOf()

    // 게터에서도 내부 배열을 그대로 돌려주면 호출부가 phoneHash.value[0] = 0 같은 식으로
    // 내부 상태를 직접 깰 수 있다. 읽을 때마다 복사본을 내줘서 내부 배열은 절대 외부에 새지 않는다.
    val value: ByteArray get() = bytes.copyOf()

    init {
        require(bytes.size == HMAC_SHA256_SIZE) { "전화번호 해시는 ${HMAC_SHA256_SIZE} 바이트여야 합니다" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PhoneHash) return false
        return bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = bytes.contentHashCode()

    companion object {
        private const val HMAC_SHA256_SIZE = 32
    }
}
