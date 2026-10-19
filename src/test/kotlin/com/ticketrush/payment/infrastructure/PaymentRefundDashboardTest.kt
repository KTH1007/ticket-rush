package com.ticketrush.payment.infrastructure

import org.assertj.core.api.Assertions.assertThat
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

class PaymentRefundDashboardTest {
    private val dashboard =
        JsonMapper.builder().build().readTree(Files.readString(Path.of("docker/grafana/provisioning/dashboards/payment-refund.json")))

    // 지표가 사라졌는데 패널이 정상처럼 초록색 0으로 보이면 알림이 조용해지는 것과 같은 착시를 만든다
    @Test
    fun `건수 패널은 지표가 없을 때 0이나 초록색으로 보이지 않는다`() {
        val stats = dashboard["panels"].filter { it["type"].asString() == "stat" }

        assertThat(stats).isNotEmpty()
        stats.forEach { panel ->
            val defaults = panel["fieldConfig"]["defaults"]
            assertThat(defaults["noValue"].asString()).isNotEqualTo("0")
            val noData = defaults["mappings"].single { it["options"]["match"].asString() == "null+nan" }
            assertThat(noData["options"]["result"]["color"].asString()).isNotEqualTo("green")
        }
    }

    @Test
    fun `건수 패널의 쿼리는 알림 규칙과 같이 인스턴스를 max나 min으로 합친다`() {
        val expressions = dashboard["panels"].toList().flatMap { panel -> panel["targets"].toList().map { it["expr"].asString() } }

        assertThat(expressions).isNotEmpty()
        assertThat(expressions).allMatch { it.startsWith("max(") || it.startsWith("min(") }
    }
}
