package com.ticketrush.support

import java.util.UUID

// 컨테이너를 재사용하면 이전 실행이 남긴 행과 겹칠 수 있어 순번 대신 난수를 쓴다
fun 유일한_예매번호(): String =
    UUID
        .randomUUID()
        .toString()
        .replace("-", "")
        .take(12)
        .uppercase()
