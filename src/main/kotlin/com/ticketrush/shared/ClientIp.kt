package com.ticketrush.shared

import jakarta.servlet.http.HttpServletRequest
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.ByteBuffer

// 속도 제한과 로그가 같은 값을 쓰도록 요청에서 한 번만 뽑는다
data class ClientIp(
    val value: String,
) {
    companion object {
        private const val PREFIX_GROUPS = 4
        private const val BYTES_PER_GROUP = 2
        private const val GROUP_MASK = 0xffff

        fun from(request: HttpServletRequest): ClientIp = of(request.remoteAddr)

        fun of(address: String): ClientIp = ClientIp(if (':' in address) normalizeIpv6(address) else address)

        private fun normalizeIpv6(address: String): String {
            val parsed = runCatching { InetAddress.getByName(address) }.getOrNull() ?: return address
            return if (parsed is Inet6Address) prefixOf(parsed) else parsed.hostAddress
        }

        // 한 사용자가 /64를 통째로 받아 주소를 바꿔 쓸 수 있어 앞 64비트로 묶는다
        private fun prefixOf(address: Inet6Address): String {
            val buffer = ByteBuffer.wrap(address.address)
            return (0 until PREFIX_GROUPS).joinToString(":", postfix = "::/64") {
                Integer.toHexString(buffer.getShort(it * BYTES_PER_GROUP).toInt() and GROUP_MASK)
            }
        }
    }
}
