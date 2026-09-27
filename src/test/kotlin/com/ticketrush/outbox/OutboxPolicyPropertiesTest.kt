package com.ticketrush.outbox

import com.ticketrush.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import kotlin.test.Test

class OutboxPolicyPropertiesTest : IntegrationTest() {
    @Autowired
    lateinit var policy: OutboxPolicyProperties

    @Test
    fun `테스트 프로필 설정값이 바인딩된다`() {
        assertThat(policy.pollInterval).isEqualTo(Duration.ofMillis(100))
        assertThat(policy.chunkSize).isEqualTo(10)
        assertThat(policy.maxAttempts).isEqualTo(5)
    }
}
