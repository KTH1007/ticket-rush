package com.ticketrush.shared.exception

// 인증/토큰 문제로 요청을 거부할 때 도메인이 상속해서 던진다.
// 이 타입 기준으로 401에 매핑한다.
@Suppress("AbstractClassCanBeConcreteClass")
abstract class UnauthorizedException(
    code: String,
    message: String,
) : BusinessException(code, message)
