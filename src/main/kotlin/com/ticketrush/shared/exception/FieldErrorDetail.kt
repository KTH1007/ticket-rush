package com.ticketrush.shared.exception

// ProblemDetail의 확장 속성(properties)에 "fieldErrors" 키로 담기는 값.
// RFC 9457은 표준 필드 외 커스텀 필드 확장을 허용한다.
data class FieldErrorDetail(
    val field: String,
    val reason: String,
)
