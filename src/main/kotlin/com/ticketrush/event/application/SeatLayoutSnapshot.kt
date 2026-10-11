package com.ticketrush.event.application

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

// 요청마다 직렬화와 압축을 하지 않도록 만들 때 한 번만 하고, 메모리를 아끼려고 gzip만 보관한다
class SeatLayoutSnapshot private constructor(
    val eventId: Long,
    val builtAt: Instant,
    val etag: String,
    val gzipJson: ByteArray,
) {
    // gzip을 받지 못하는 클라이언트용
    fun json(): ByteArray = GZIPInputStream(ByteArrayInputStream(gzipJson)).use { it.readBytes() }

    companion object {
        private const val ETAG_LENGTH = 32

        // 내용이 같으면 다시 만들어도 ETag가 같아서 클라이언트 캐시가 유지된다
        fun from(
            eventId: Long,
            builtAt: Instant,
            json: ByteArray,
        ): SeatLayoutSnapshot = SeatLayoutSnapshot(eventId, builtAt, etagOf(json), gzip(json))

        private fun etagOf(json: ByteArray): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(json)
                .toHexString()
                .take(ETAG_LENGTH)

        private fun gzip(json: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { it.write(json) }
            return out.toByteArray()
        }
    }
}
