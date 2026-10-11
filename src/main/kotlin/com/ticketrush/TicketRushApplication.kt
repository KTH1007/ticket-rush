package com.ticketrush

import com.ticketrush.shared.AppTimeZone
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling
import java.util.TimeZone

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
class TicketRushApplication

@Suppress("SpreadOperator") // runApplication의 vararg 시그니처를 쓰려면 스프레드가 불가피하다
fun main(args: Array<String>) {
    TimeZone.setDefault(TimeZone.getTimeZone(AppTimeZone.ID)) // logback이 JVM 기본 존으로 시각을 찍어서 로그만 KST로 맞춘다
    runApplication<TicketRushApplication>(*args)
}
