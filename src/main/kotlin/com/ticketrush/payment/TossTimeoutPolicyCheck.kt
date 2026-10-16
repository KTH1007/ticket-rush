package com.ticketrush.payment

import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

// stale-claim-timeout과 min-hold-remaining은 Toss 타임아웃에서 계산한 하한이라, 한쪽만 바뀌어 어긋나면 기동 때 드러나게 한다.
// 읽기 타임아웃은 읽기 사이의 대기라 호출 전체의 상한이 아니므로 이 합은 어디까지나 휴리스틱이다
@Profile("toss")
@Component
class TossTimeoutPolicyCheck(
    toss: TossProperties,
    payment: PaymentPolicyProperties,
) {
    init {
        val oneCall = toss.connectTimeout + toss.readTimeout
        // 이어받기 시간이 호출 한 번에 필요한 하한보다 짧으면 아직 호출 중인 클레임을 다른 요청이 가로챌 수 있다
        require(payment.staleClaimTimeout >= oneCall) {
            "staleClaimTimeout은 Toss 호출 한 번에 필요한 하한(연결+읽기)보다 짧을 수 없습니다: staleClaimTimeout=${payment.staleClaimTimeout}, 호출=$oneCall"
        }
        // 승인 뒤 조회까지 붙는 경로(호출 두 번)에 필요한 하한
        require(payment.minHoldRemaining >= oneCall.multipliedBy(2)) {
            "minHoldRemaining은 Toss 호출 두 번에 필요한 하한보다 짧을 수 없습니다: minHoldRemaining=${payment.minHoldRemaining}, 호출 두 번=${oneCall.multipliedBy(
                2,
            )}"
        }
    }
}
