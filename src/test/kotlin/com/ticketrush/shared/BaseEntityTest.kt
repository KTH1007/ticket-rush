package com.ticketrush.shared

import com.ticketrush.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.Test

class BaseEntityTest : IntegrationTest() {
    @Autowired
    lateinit var probeRepository: BaseEntityTestProbeRepository

    @Test
    fun `저장하면 createdAt과 updatedAt이 채워진다`() {
        val saved = probeRepository.saveAndFlush(BaseEntityTestProbe())

        assertThat(saved.createdAt).isNotNull()
        assertThat(saved.updatedAt).isNotNull()
    }

    @Test
    fun `수정하면 updatedAt만 바뀌고 createdAt은 그대로다`() {
        val saved = probeRepository.saveAndFlush(BaseEntityTestProbe())
        val createdAtBeforeUpdate = saved.createdAt
        val updatedAtBeforeUpdate = saved.updatedAt

        saved.note = "changed"
        val updated = probeRepository.saveAndFlush(saved)

        assertThat(updated.createdAt).isEqualTo(createdAtBeforeUpdate)
        assertThat(updated.updatedAt).isAfter(updatedAtBeforeUpdate)
    }
}
