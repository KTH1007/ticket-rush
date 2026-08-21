package com.ticketrush.shared.response

import org.assertj.core.api.Assertions.assertThat
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import kotlin.test.Test

class PageResponseTest {
    @Test
    fun `Page의 내용을 PageResponse로 변환한다`() {
        // given
        val page = PageImpl(listOf("a", "b"), PageRequest.of(0, 2), 5)

        // when
        val response = PageResponse.from(page)

        // then
        assertThat(response.content).containsExactly("a", "b")
        assertThat(response.page).isEqualTo(0)
        assertThat(response.size).isEqualTo(2)
        assertThat(response.totalElements).isEqualTo(5)
        assertThat(response.totalPages).isEqualTo(3)
    }

    @Test
    fun `마지막 페이지가 아니면 hasNext가 true다`() {
        // given
        val page = PageImpl(listOf("a", "b"), PageRequest.of(0, 2), 5)

        // when
        val response = PageResponse.from(page)

        // then
        assertThat(response.hasNext).isTrue()
    }

    @Test
    fun `마지막 페이지면 hasNext가 false다`() {
        // given
        val page = PageImpl(listOf("e"), PageRequest.of(2, 2), 5)

        // when
        val response = PageResponse.from(page)

        // then
        assertThat(response.hasNext).isFalse()
    }

    @Test
    fun `빈 페이지도 변환된다`() {
        // given
        val page = PageImpl<String>(emptyList(), PageRequest.of(0, 10), 0)

        // when
        val response = PageResponse.from(page)

        // then
        assertThat(response.content).isEmpty()
        assertThat(response.totalElements).isEqualTo(0)
        assertThat(response.totalPages).isEqualTo(0)
        assertThat(response.hasNext).isFalse()
    }
}
