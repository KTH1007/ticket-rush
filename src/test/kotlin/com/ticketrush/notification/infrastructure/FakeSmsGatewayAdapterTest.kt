package com.ticketrush.notification.infrastructure

import org.assertj.core.api.Assertions.assertThatCode
import kotlin.test.Test

class FakeSmsGatewayAdapterTest {
    private val adapter = FakeSmsGatewayAdapter()

    @Test
    fun `발송을 호출하면 예외 없이 끝난다`() {
        assertThatCode { adapter.sendSms("01099999999", "[티켓러시] 예매가 완료됐습니다. 예매번호: RES00000001") }
            .doesNotThrowAnyException()
    }
}
