package com.ticketrush.shared

import com.ticketrush.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.Test

class PhoneEncryptorTest : IntegrationTest() {
    @Autowired
    lateinit var phoneEncryptor: PhoneEncryptor

    @Test
    fun `암호화한 값을 복호화하면 원문 전화번호가 그대로 나온다`() {
        // given
        val phoneNumber = "01099999999"

        // when
        val encrypted = phoneEncryptor.encrypt(phoneNumber)
        val decrypted = phoneEncryptor.decrypt(encrypted)

        // then
        assertThat(decrypted).isEqualTo(phoneNumber)
    }

    @Test
    fun `같은 전화번호도 암호화할 때마다 다른 값이 나온다`() {
        // given
        val phoneNumber = "01099999999"

        // when
        val first = phoneEncryptor.encrypt(phoneNumber)
        val second = phoneEncryptor.encrypt(phoneNumber)

        // then
        assertThat(first.value).isNotEqualTo(second.value)
    }
}
