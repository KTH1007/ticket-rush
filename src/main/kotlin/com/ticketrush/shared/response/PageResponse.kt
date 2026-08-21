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
    // page + 1로 더하면 page가 Int.MAX_VALUE일 때 오버플로하므로 뺄셈 비교로 계산한다.
    val hasNext: Boolean get() = page < totalPages - 1

    init {
        require(page >= 0) { "page는 0 이상이어야 합니다: $page" }
        require(size > 0) { "size는 1 이상이어야 합니다: $size" }
        require(totalElements >= 0) { "totalElements는 0 이상이어야 합니다: $totalElements" }
        require(totalPages >= 0) { "totalPages는 0 이상이어야 합니다: $totalPages" }
    }

    companion object {
        // Pageable.unpaged()로 조회한 Page는 size=0, totalPages=1이 되어 이 타입의
        // 불변식과 안 맞는다. 페이지네이션된 Page만 지원한다.
        fun <T : Any> from(page: Page<T>): PageResponse<T> {
            require(page.pageable.isPaged) { "PageResponse.from()은 페이지네이션된 Page만 지원한다" }
            return PageResponse(
                content = page.content,
                page = page.number,
                size = page.size,
                totalElements = page.totalElements,
                totalPages = page.totalPages,
            )
        }
    }
}
