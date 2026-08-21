package com.ticketrush.shared.exception

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// GlobalExceptionHandlerTest 전용. 실제 도메인 컨트롤러가 아직 없어(#43 이전) 예외 매핑만
// 검증하기 위한 최소 엔드포인트만 가진 컨트롤러.
@RestController
@RequestMapping("/test")
class ExceptionTestController {
    @PostMapping("/not-found")
    fun notFound(): Nothing = throw SampleNotFoundException()

    @PostMapping("/business")
    fun business(): Nothing = throw SampleBusinessException()

    @PostMapping("/unexpected")
    fun unexpected(): Nothing = error("boom")

    @PostMapping("/validate")
    fun validate(
        @Valid @RequestBody request: SampleRequest,
    ): SampleRequest = request
}

class SampleNotFoundException : NotFoundException(code = "SAMPLE_NOT_FOUND", message = "샘플을 찾을 수 없습니다")

class SampleBusinessException : BusinessException(code = "SAMPLE_CONFLICT", message = "샘플 충돌이 발생했습니다")

data class SampleRequest(
    @field:NotBlank
    val name: String,
)
