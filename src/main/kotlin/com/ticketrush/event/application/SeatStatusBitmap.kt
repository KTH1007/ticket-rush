package com.ticketrush.event.application

import com.ticketrush.reservation.domain.SeatStatus
import com.ticketrush.reservation.domain.SeatStatusRow

// ordinal i는 바이트 i/4의 상위 비트부터 (i%4)번째 2비트. 클라이언트의 디코딩 규칙이라 바꾸면 호환이 깨진다
object SeatStatusBitmap {
    const val BITS_PER_SEAT = 2
    private const val SEATS_PER_BYTE = 8 / BITS_PER_SEAT

    fun seatCount(rows: List<SeatStatusRow>): Int = if (rows.isEmpty()) 0 else rows.maxOf { it.ordinal } + 1

    fun encode(rows: List<SeatStatusRow>): ByteArray {
        val bytes = ByteArray((seatCount(rows) + SEATS_PER_BYTE - 1) / SEATS_PER_BYTE)
        rows.forEach { row ->
            val index = row.ordinal / SEATS_PER_BYTE
            bytes[index] = (bytes[index].toInt() or (code(row.status) shl shift(row.ordinal))).toByte()
        }
        return bytes
    }

    private fun shift(ordinal: Int) = (SEATS_PER_BYTE - 1 - ordinal % SEATS_PER_BYTE) * BITS_PER_SEAT

    private fun code(status: SeatStatus) =
        when (status) {
            SeatStatus.AVAILABLE -> 0
            SeatStatus.HELD -> 1
            SeatStatus.SOLD -> 2
        }
}
