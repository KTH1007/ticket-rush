package com.ticketrush.shared.exception

import io.github.oshai.kotlinlogging.KotlinLogging
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

private val appLogger = KotlinLogging.logger {}

// 에러 응답은 RFC 9457(Problem Details for HTTP APIs) 표준을 따른다. 성공 응답은
// 감싸지 않고 DTO를 그대로 반환한다.
//
// ResponseEntityExceptionHandler를 상속해 스프링 표준 MVC 예외(405/404/400 등)는 부모의
// 기본 처리에 맡기고, 우리 도메인 예외/검증 실패만 오버라이드/핸들러로 다룬다. Exception
// catch-all만 두면 이 표준 예외들까지 전부 500으로 뭉개진다(실제로 재현해서 확인함).
@Order(Ordered.HIGHEST_PRECEDENCE) // 순서 미지정 내부 ControllerAdvice보다 항상 먼저 걸리게
@RestControllerAdvice
class GlobalExceptionHandler : ResponseEntityExceptionHandler() {
    @ExceptionHandler(NotFoundException::class)
    fun handleNotFound(e: NotFoundException): ResponseEntity<ProblemDetail> {
        val problem = problemDetail(HttpStatus.NOT_FOUND, e.code, e.message ?: e.code)
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem)
    }

    // NotFoundException이 아닌 나머지 도메인 예외(좌석 중복 선점, 잔액 부족 등). 기본 400으로
    // 처리한다. 구체적인 상태 코드(409 등)가 필요한 도메인이 생기면 그 예외 전용 핸들러를 추가한다.
    @ExceptionHandler(BusinessException::class)
    fun handleBusiness(e: BusinessException): ResponseEntity<ProblemDetail> {
        val problem = problemDetail(HttpStatus.BAD_REQUEST, e.code, e.message ?: e.code)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem)
    }

    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any> {
        val problem = problemDetail(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 값이 유효하지 않습니다")
        problem.setProperty(
            "fieldErrors",
            ex.bindingResult.fieldErrors.map {
                FieldErrorDetail(field = it.field, reason = it.defaultMessage ?: "유효하지 않은 값입니다")
            },
        )
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem)
    }

    // 예상 못한 예외까지 표준 포맷으로 감싼다. 원인 추적을 위해 반드시 로그를 남긴다.
    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception): ResponseEntity<ProblemDetail> {
        appLogger.error(e) { "처리되지 않은 예외 발생" }
        val problem = problemDetail(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "일시적인 오류가 발생했습니다")
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem)
    }

    // title은 RFC 9457 의미대로 사람이 읽는 문구(상태 코드의 reasonPhrase)로 둔다. code는
    // 클라이언트가 분기할 기계 판독용 식별자라 성격이 달라서 확장 속성으로 분리한다.
    private fun problemDetail(
        status: HttpStatus,
        code: String,
        detail: String,
    ): ProblemDetail {
        val problem = ProblemDetail.forStatusAndDetail(status, detail)
        problem.title = status.reasonPhrase
        problem.setProperty("code", code)
        problem.setProperty("traceId", MDC.get("traceId"))
        return problem
    }
}
