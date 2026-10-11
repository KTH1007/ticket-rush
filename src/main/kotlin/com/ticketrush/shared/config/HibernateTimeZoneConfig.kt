package com.ticketrush.shared.config

import com.ticketrush.shared.AppTimeZone
import org.hibernate.cfg.AvailableSettings
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class HibernateTimeZoneConfig {
    // yml이나 환경변수로 Clock과 다른 존이 들어오지 못하게 막는다. customizer는 둘보다 나중에 적용된다
    @Bean
    fun jdbcTimeZoneCustomizer() = HibernatePropertiesCustomizer { it[AvailableSettings.JDBC_TIME_ZONE] = AppTimeZone.ID.id }
}
