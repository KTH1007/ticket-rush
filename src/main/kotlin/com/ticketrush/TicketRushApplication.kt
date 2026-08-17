package com.ticketrush

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaAuditing
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableJpaAuditing
@EnableScheduling
class TicketRushApplication

@Suppress("SpreadOperator") // runApplication의 vararg 시그니처를 쓰려면 스프레드가 불가피하다
fun main(args: Array<String>) {
    runApplication<TicketRushApplication>(*args)
}
