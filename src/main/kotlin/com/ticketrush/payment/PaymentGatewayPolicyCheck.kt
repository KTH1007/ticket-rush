package com.ticketrush.payment

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

// 가짜 PG는 모든 결제를 승인하고 환불은 실제로 하지 않으며 조회는 항상 없음으로 답한다. 운영에 뜨면 돈 없이 티켓이 확정되므로 개발용 프로필에서만 켠다
private val FAKE_ALLOWED_PROFILES = listOf("local", "docker", "test")

@Component
class PaymentGatewayPolicyCheck(
    environment: Environment,
) {
    init {
        val gateway = PaymentGatewayType.from(environment)
        // 예전에는 toss 프로필이 Toss를 켰다. 그 프로필만 주던 구성이 가짜로 뜨는 것을 막는다
        check(gateway == PaymentGatewayType.TOSS || !environment.acceptsProfiles(Profiles.of("toss"))) {
            "toss 프로필이 켜져 있는데 $PAYMENT_GATEWAY_PROPERTY=${gateway.name.lowercase()} 입니다. 이제 PG는 이 설정으로만 정해집니다"
        }
        if (gateway == PaymentGatewayType.FAKE) {
            check(environment.acceptsProfiles(Profiles.of(FAKE_ALLOWED_PROFILES.joinToString(" | ")))) {
                "가짜 PG는 ${FAKE_ALLOWED_PROFILES.joinToString()} 프로필에서만 켤 수 있습니다: 활성 프로필=${environment.activeProfiles.joinToString()}"
            }
            logger.warn { "가짜 PG가 켜져 있습니다. 모든 결제를 승인하고 환불은 실제로 이뤄지지 않습니다. 운영에서 보이면 즉시 중단해야 합니다" }
        } else {
            logger.info { "PG는 Toss입니다" }
        }
    }
}
