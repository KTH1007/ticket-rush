package com.ticketrush.shared.exception

@Suppress("AbstractClassCanBeConcreteClass")
abstract class ForbiddenException(
    code: String,
    message: String,
) : BusinessException(code, message)
