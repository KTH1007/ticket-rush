package com.ticketrush.shared.exception

// 도메인 예외 공통 베이스. code는 클라이언트가 분기할 안정적 식별자다. abstract 멤버가 없어도
// concrete로 바꾸면 이 타입을 직접 던질 수 있게 돼 "항상 구체 예외를 던진다"는 규칙이 깨진다.
@Suppress("AbstractClassCanBeConcreteClass")
abstract class BusinessException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
