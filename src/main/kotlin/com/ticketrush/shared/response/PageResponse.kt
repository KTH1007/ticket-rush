package com.ticketrush.shared.response

import org.springframework.data.domain.Page

// 목록 조회 API의 공통 응답 포맷. Spring Data Page를 그대로 직렬화하면
// pageable/sort 등 내부 구현이 그대로 노출돼 별도 DTO로 감싼다.
data class PageResponse<T : Any>(
    val content: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
) {
    // page/totalPages로부터 계산 가능한 파생값이라 별도 필드로 저장하지 않는다.
    // 저장 필드였다면 직접 생성 시 값이 어긋나도 막을 방법이 없었다.
    val hasNext: Boolean get() = page + 1 < totalPages

    init {
        require(page >= 0) { "page는 0 이상이어야 합니다: $page" }
        require(size > 0) { "size는 1 이상이어야 합니다: $size" }
        require(totalElements >= 0) { "totalElements는 0 이상이어야 합니다: $totalElements" }
        require(totalPages >= 0) { "totalPages는 0 이상이어야 합니다: $totalPages" }
    }

    companion object {
        fun <T : Any> from(page: Page<T>): PageResponse<T> =
            PageResponse(
                content = page.content,
                page = page.number,
                size = page.size,
                totalElements = page.totalElements,
                totalPages = page.totalPages,
            )
    }
}
