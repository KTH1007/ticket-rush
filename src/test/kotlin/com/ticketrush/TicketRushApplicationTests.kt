package com.ticketrush

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@ActiveProfiles("test")
class TicketRushApplicationTests {
    @Test
    @Suppress("EmptyFunctionBlock") // 컨텍스트 로딩 스모크 테스트 — 예외 없이 뜨면 통과하는 관용구
    fun contextLoads() {
    }
}
