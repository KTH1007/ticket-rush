package com.ticketrush.event.presentation

object AcceptEncoding {
    private const val DEFAULT_Q = 1.0
    private const val REJECTED_Q = 0.0

    // 명시된 gzip의 q가 와일드카드(*)보다 우선한다. 헤더가 없으면 gzip을 쓰지 않는다
    fun acceptsGzip(header: String?): Boolean {
        val qByCoding =
            header
                .orEmpty()
                .split(',')
                .mapNotNull(::parse)
                .toMap()
        return (qByCoding["gzip"] ?: qByCoding["*"] ?: REJECTED_Q) > 0.0
    }

    private fun parse(part: String): Pair<String, Double>? {
        val tokens = part.trim().split(';').map { it.trim() }
        val coding = tokens.first().lowercase()
        if (coding.isEmpty()) return null
        val qToken = tokens.drop(1).firstOrNull { it.startsWith("q=", ignoreCase = true) }
        // 깨진 q를 허용으로 읽으면 못 푸는 클라이언트에게 gzip을 줄 수 있어서 거부로 읽는다
        val q = qToken?.substring(2)?.toDoubleOrNull() ?: if (qToken == null) DEFAULT_Q else REJECTED_Q
        return coding to q
    }
}
