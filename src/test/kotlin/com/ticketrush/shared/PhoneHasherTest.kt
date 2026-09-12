package com.ticketrush.shared

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class PhoneHasherTest {
    private val hasher = PhoneHasher(key = "test-secret-key")

    @Test
    fun `같은 전화번호는 항상 같은 해시를 만든다`() {
        // when
        val hash1 = hasher.hash("01012345678")
        val hash2 = hasher.hash("01012345678")

        // then
        assertThat(hash1).isEqualTo(hash2)
    }

    @Test
    fun `다른 전화번호는 다른 해시를 만든다`() {
        // when
        val hash1 = hasher.hash("01012345678")
        val hash2 = hasher.hash("01087654321")

        // then
        assertThat(hash1).isNotEqualTo(hash2)
    }

    @Test
    fun `해시 결과는 32바이트다`() {
        // when
        val hash = hasher.hash("01012345678")

        // then
        assertThat(hash.value).hasSize(32)
    }

    @Test
    fun `키가 다르면 같은 전화번호도 다른 해시가 나온다`() {
        // given
        val otherHasher = PhoneHasher(key = "other-secret-key")

        // when
        val hash1 = hasher.hash("01012345678")
        val hash2 = otherHasher.hash("01012345678")

        // then
        assertThat(hash1).isNotEqualTo(hash2)
    }
}
