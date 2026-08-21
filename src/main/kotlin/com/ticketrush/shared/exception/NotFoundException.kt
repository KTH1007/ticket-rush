package com.ticketrush.shared.exception

// 대상을 찾을 수 없을 때 도메인이 상속해서 던진다. GlobalExceptionHandler가 이 타입 기준으로
// 404에 매핑한다. abstract 멤버가 없어도 abstract를 유지하는 이유는 BusinessException과 같다.
@Suppress("AbstractClassCanBeConcreteClass")
abstract class NotFoundException(
    code: String,
    message: String,
) : BusinessException(code, message)
