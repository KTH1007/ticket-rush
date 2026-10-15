package com.ticketrush.support

import com.ticketrush.shared.ClientIp

// IP 한도를 거는 테스트가 아니면 같은 IP를 쓴다. 테스트 설정이 한도를 크게 올려 둔다
val testClientIp = ClientIp("203.0.113.1")
