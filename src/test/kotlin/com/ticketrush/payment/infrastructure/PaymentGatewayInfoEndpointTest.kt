package com.ticketrush.payment.infrastructure

import com.ticketrush.support.IntegrationTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.Test

// 인스턴스마다 PG 구현이 같은지 사람이 직접 확인하는 경로다
@AutoConfigureMockMvc
class PaymentGatewayInfoEndpointTest : IntegrationTest() {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `info 엔드포인트가 이 인스턴스의 PG 구현을 알려 준다`() {
        mockMvc
            .perform(get("/actuator/info"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.payment.gateway").value("fake"))
    }
}
