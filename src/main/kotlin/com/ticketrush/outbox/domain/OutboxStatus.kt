package com.ticketrush.outbox.domain

enum class OutboxStatus(
    val description: String,
) {
    PENDING("처리 대기 중"),
    PROCESSING("릴레이가 claim해서 처리 중"),
    DONE("처리 완료"),
    FAILED("재시도 임계치 초과로 영구 실패"),
}
