package com.ticketrush.shared.config

import com.querydsl.jpa.impl.JPAQueryFactory
import com.ticketrush.shared.BaseEntityTestProbe
import com.ticketrush.shared.BaseEntityTestProbeRepository
import com.ticketrush.shared.QBaseEntityTestProbe
import com.ticketrush.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.Test

class QueryDslConfigTest : IntegrationTest() {
    @Autowired
    lateinit var queryFactory: JPAQueryFactory

    @Autowired
    lateinit var probeRepository: BaseEntityTestProbeRepository

    @Test
    fun `JPAQueryFactory 빈이 등록된다`() {
        assertThat(queryFactory).isNotNull()
    }

    @Test
    fun `Q타입으로 select 쿼리가 실행된다`() {
        val saved = probeRepository.saveAndFlush(BaseEntityTestProbe().apply { note = "querydsl" })

        val found =
            queryFactory
                .selectFrom(QBaseEntityTestProbe.baseEntityTestProbe)
                .where(QBaseEntityTestProbe.baseEntityTestProbe.id.eq(saved.id))
                .fetchOne()

        assertThat(found?.note).isEqualTo("querydsl")
    }
}
