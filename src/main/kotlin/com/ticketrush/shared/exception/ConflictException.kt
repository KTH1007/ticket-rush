package com.ticketrush.shared.exception

// 이미 선점된 좌석, 1인 2매 초과처럼 "요청 자체는 유효하지만 현재 상태와 충돌"하는
// 경우 도메인이 상속해서 던진다. GlobalExceptionHandler가 이 타입 기준으로 409에 매핑한다.
@Suppress("AbstractClassCanBeConcreteClass")
abstract class ConflictException(
    code: String,
    message: String,
) : BusinessException(code, message)
