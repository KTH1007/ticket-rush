package com.ticketrush.shared

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.test.Test

class PhoneHashTest {
    @Test
    fun `내용이 같은 바이트 배열은 동등하다고 판단한다`() {
        // given
        val hash1 = PhoneHash(ByteArray(32) { 1 })
        val hash2 = PhoneHash(ByteArray(32) { 1 })

        // when & then
        assertThat(hash1).isEqualTo(hash2)
    }

    @Test
    fun `내용이 다른 바이트 배열은 동등하지 않다`() {
        // given
        val hash1 = PhoneHash(ByteArray(32) { 1 })
        val hash2 = PhoneHash(ByteArray(32) { 2 })

        // when & then
        assertThat(hash1).isNotEqualTo(hash2)
    }

    @Test
    fun `동등한 값은 hashCode도 같다`() {
        // given
        val hash1 = PhoneHash(ByteArray(32) { 1 })
        val hash2 = PhoneHash(ByteArray(32) { 1 })

        // when & then
        assertThat(hash1.hashCode()).isEqualTo(hash2.hashCode())
    }

    @Test
    fun `HMAC-SHA256 크기(32바이트)가 아니면 생성할 수 없다`() {
        // when & then
        assertThatThrownBy { PhoneHash(ByteArray(16)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
