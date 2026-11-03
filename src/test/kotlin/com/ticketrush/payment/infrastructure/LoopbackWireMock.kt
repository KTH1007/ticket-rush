package com.ticketrush.payment.infrastructure

import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.junit5.WireMockExtension

// 와일드카드로 바인드하면 같은 번호를 127.0.0.1에 연 다른 프로세스가 localhost 연결을 가져갈 수 있어 루프백에만 바인드한다
fun loopbackWireMock(): WireMockExtension =
    WireMockExtension
        .newInstance()
        .options(wireMockConfig().dynamicPort().bindAddress(LOOPBACK))
        .build()

fun WireMockExtension.loopbackUrl(): String = "http://$LOOPBACK:$port"

private const val LOOPBACK = "127.0.0.1"
