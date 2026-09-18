package com.ticketrush.shared.exception

@Suppress("AbstractClassCanBeConcreteClass")
abstract class TooManyRequestsException(
    code: String,
    message: String,
) : BusinessException(code, message)
