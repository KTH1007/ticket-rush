package com.ticketrush.queue.application

import com.ticketrush.queue.domain.InvalidQueueTokenException
import com.ticketrush.queue.domain.QueueActiveExpiredException
import com.ticketrush.queue.domain.QueueNotActiveException
import com.ticketrush.queue.domain.QueuePhoneMismatchException
import com.ticketrush.queue.domain.QueueRepositoryPort
import com.ticketrush.shared.PhoneHash
import com.ticketrush.support.IntegrationTest
import com.ticketrush.support.열린_공연에_등록
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.UUID
import kotlin.test.Test

class QueueCommandServiceAdmitTest : IntegrationTest() {
    @Autowired
    lateinit var queueRepository: QueueRepositoryPort

    @Autowired
    lateinit var queueCommandService: QueueCommandService

    @Autowired
    lateinit var redisTemplate: StringRedisTemplate

    private val eventId = System.nanoTime()

    private val phone = PhoneHash(ByteArray(32) { 1 })

    private val otherPhone = PhoneHash(ByteArray(32) { 2 })

    private fun 승격된_토큰(): String {
        val token = UUID.randomUUID().toString()
        queueRepository.열린_공연에_등록(eventId, token)
        queueRepository.promote(eventId, 1)
        return token
    }

    @Test
    fun `토큰이 null이면 InvalidQueueTokenException이 발생한다`() {
        // when & then
        assertThatThrownBy { queueCommandService.admit(eventId, null, phone) }
            .isInstanceOf(InvalidQueueTokenException::class.java)
    }

    @Test
    fun `등록하지 않은 토큰이면 InvalidQueueTokenException이 발생한다`() {
        // when & then
        assertThatThrownBy { queueCommandService.admit(eventId, UUID.randomUUID().toString(), phone) }
            .isInstanceOf(InvalidQueueTokenException::class.java)
    }

    @Test
    fun `등록됐지만 아직 승격되지 않은 토큰이면 QueueNotActiveException이 발생한다`() {
        // given
        val token = UUID.randomUUID().toString()
        queueRepository.열린_공연에_등록(eventId, token)

        // when & then
        assertThatThrownBy { queueCommandService.admit(eventId, token, phone) }
            .isInstanceOf(QueueNotActiveException::class.java)
    }

    @Test
    fun `활성 구간이 끝난 토큰이면 QueueActiveExpiredException이 발생한다`() {
        // given
        val token = 승격된_토큰()
        redisTemplate.delete("queue:$eventId:active:$token")

        // when & then
        assertThatThrownBy { queueCommandService.admit(eventId, token, phone) }
            .isInstanceOf(QueueActiveExpiredException::class.java)
    }

    @Test
    fun `승격된 토큰은 통과하고 같은 번호로 다시 써도 통과한다`() {
        // given
        val token = 승격된_토큰()

        // when & then
        assertThatCode {
            queueCommandService.admit(eventId, token, phone)
            queueCommandService.admit(eventId, token, phone)
        }.doesNotThrowAnyException()
    }

    @Test
    fun `이미 다른 번호에 묶인 토큰이면 QueuePhoneMismatchException이 발생한다`() {
        // given
        val token = 승격된_토큰()
        queueCommandService.admit(eventId, token, phone)

        // when & then
        assertThatThrownBy { queueCommandService.admit(eventId, token, otherPhone) }
            .isInstanceOf(QueuePhoneMismatchException::class.java)
    }
}
