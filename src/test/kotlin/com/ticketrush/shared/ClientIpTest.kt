package com.ticketrush.shared

import org.assertj.core.api.Assertions.assertThat
import org.springframework.mock.web.MockHttpServletRequest
import kotlin.test.Test

class ClientIpTest {
    @Test
    fun `IPv4는 그대로 쓴다`() {
        assertThat(ClientIp.of("203.0.113.7").value).isEqualTo("203.0.113.7")
    }

    @Test
    fun `같은 64비트 앞부분을 쓰는 IPv6는 같은 값이 된다`() {
        val first = ClientIp.of("2001:db8:1:2:aaaa:bbbb:cccc:dddd")
        val second = ClientIp.of("2001:db8:1:2::1")

        assertThat(first).isEqualTo(second)
        assertThat(first.value).isEqualTo("2001:db8:1:2::/64")
    }

    @Test
    fun `앞부분이 다른 IPv6는 다른 값이 된다`() {
        assertThat(ClientIp.of("2001:db8:1:2::1")).isNotEqualTo(ClientIp.of("2001:db8:1:3::1"))
    }

    @Test
    fun `IPv4로 매핑된 IPv6는 IPv4로 쓴다`() {
        assertThat(ClientIp.of("::ffff:203.0.113.7").value).isEqualTo("203.0.113.7")
    }

    @Test
    fun `해석할 수 없는 값은 원문을 그대로 쓴다`() {
        assertThat(ClientIp.of("not:an:ip:address:zzzz").value).isEqualTo("not:an:ip:address:zzzz")
    }

    @Test
    fun `요청에서는 remoteAddr를 읽는다`() {
        val request = MockHttpServletRequest().apply { remoteAddr = "198.51.100.9" }

        assertThat(ClientIp.from(request).value).isEqualTo("198.51.100.9")
    }
}
