package com.ticketrush.shared

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// 조회는 PhoneHasher(단방향)로, 발송은 이걸로(양방향) 나눈다. AES-256-GCM을 쓰고
// nonce는 매 호출마다 새로 뽑아 암호문 앞에 붙인다(nonce 재사용 시 GCM이 취약해짐).
@Component
class PhoneEncryptor(
    @Value("\${ticket-rush.security.phone-encryption-key}") base64Key: String,
) {
    private val keySpec = SecretKeySpec(Base64.getDecoder().decode(base64Key), "AES")
    private val random = SecureRandom()

    fun encrypt(phoneNumber: String): EncryptedPhone {
        val nonce = ByteArray(GCM_NONCE_LENGTH).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce))
        val ciphertext = cipher.doFinal(phoneNumber.toByteArray())
        return EncryptedPhone(nonce + ciphertext)
    }

    fun decrypt(encryptedPhone: EncryptedPhone): String {
        val bytes = encryptedPhone.value
        val nonce = bytes.copyOfRange(0, GCM_NONCE_LENGTH)
        val ciphertext = bytes.copyOfRange(GCM_NONCE_LENGTH, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce))
        return String(cipher.doFinal(ciphertext))
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_NONCE_LENGTH = 12
        private const val GCM_TAG_LENGTH_BITS = 128
    }
}
