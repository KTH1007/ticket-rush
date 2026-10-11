package com.ticketrush.event.presentation

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class AcceptEncodingTest {
    @Test
    fun `헤더가 없으면 gzip을 쓰지 않는다`() {
        assertThat(AcceptEncoding.acceptsGzip(null)).isFalse()
        assertThat(AcceptEncoding.acceptsGzip("")).isFalse()
    }

    @Test
    fun `gzip이 들어 있으면 쓴다`() {
        assertThat(AcceptEncoding.acceptsGzip("gzip")).isTrue()
        assertThat(AcceptEncoding.acceptsGzip("gzip, deflate, br")).isTrue()
        assertThat(AcceptEncoding.acceptsGzip("br, gzip;q=0.8")).isTrue()
    }

    @Test
    fun `대소문자와 공백은 가리지 않는다`() {
        assertThat(AcceptEncoding.acceptsGzip(" GZIP ; Q=0.5 ")).isTrue()
    }

    // gzip;q=0은 gzip을 받지 않겠다는 뜻이다. 그래도 gzip 본문을 주면 클라이언트가 해석하지 못한다
    @Test
    fun `q가 0이면 거부한다`() {
        assertThat(AcceptEncoding.acceptsGzip("gzip;q=0")).isFalse()
        assertThat(AcceptEncoding.acceptsGzip("gzip;q=0.0, deflate")).isFalse()
    }

    @Test
    fun `와일드카드는 gzip이 따로 없을 때 따른다`() {
        assertThat(AcceptEncoding.acceptsGzip("*")).isTrue()
        assertThat(AcceptEncoding.acceptsGzip("*;q=0")).isFalse()
        assertThat(AcceptEncoding.acceptsGzip("identity")).isFalse()
    }

    // 명시된 값이 와일드카드보다 우선한다
    @Test
    fun `gzip을 명시적으로 거부하면 와일드카드가 허용해도 쓰지 않는다`() {
        assertThat(AcceptEncoding.acceptsGzip("gzip;q=0, *")).isFalse()
        assertThat(AcceptEncoding.acceptsGzip("gzip, *;q=0")).isTrue()
    }

    // 알 수 없는 q를 허용으로 읽으면 못 푸는 클라이언트에게 gzip을 줄 수 있어서 거부로 읽는다
    @Test
    fun `q 형식이 깨지면 거부한다`() {
        assertThat(AcceptEncoding.acceptsGzip("gzip;q=abc")).isFalse()
    }
}
