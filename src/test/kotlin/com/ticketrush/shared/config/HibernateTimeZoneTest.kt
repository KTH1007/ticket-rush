package com.ticketrush.shared.config

import com.ticketrush.shared.AppTimeZone
import com.ticketrush.support.IntegrationTest
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.engine.spi.SessionFactoryImplementor
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import kotlin.test.Test

@TestPropertySource(properties = ["spring.jpa.properties.hibernate.jdbc.time_zone=UTC"])
class HibernateTimeZoneTest : IntegrationTest() {
    @Autowired
    lateinit var entityManagerFactory: EntityManagerFactory

    @Test
    fun `설정이 다른 존을 가리켜도 Hibernate의 JDBC 시간대는 AppTimeZone이다`() {
        val jdbcTimeZone = entityManagerFactory.unwrap(SessionFactoryImplementor::class.java).sessionFactoryOptions.jdbcTimeZone

        assertThat(jdbcTimeZone.toZoneId()).isEqualTo(AppTimeZone.ID)
    }
}
