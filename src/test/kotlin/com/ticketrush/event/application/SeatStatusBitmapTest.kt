package com.ticketrush.event.application

import com.ticketrush.reservation.domain.SeatStatus
import com.ticketrush.reservation.domain.SeatStatusRow
import org.assertj.core.api.Assertions.assertThat
import kotlin.random.Random
import kotlin.test.Test

class SeatStatusBitmapTest {
    private fun row(
        ordinal: Int,
        status: SeatStatus,
    ) = SeatStatusRow(ordinal, status)

    // 클라이언트가 같은 규칙으로 읽는다는 것을 고정하려고 인코더와 따로 둔 디코더
    private fun statusOf(
        bytes: ByteArray,
        ordinal: Int,
    ): SeatStatus =
        when (val code = (bytes[ordinal / 4].toInt() shr ((3 - ordinal % 4) * 2)) and 0b11) {
            0 -> SeatStatus.AVAILABLE
            1 -> SeatStatus.HELD
            2 -> SeatStatus.SOLD
            else -> error("알 수 없는 상태 코드: $code")
        }

    private fun byteAt(
        bytes: ByteArray,
        index: Int,
    ) = bytes[index].toInt() and 0xFF

    @Test
    fun `좌석이 없으면 빈 배열이고 좌석 수는 0이다`() {
        assertThat(SeatStatusBitmap.encode(emptyList())).isEmpty()
        assertThat(SeatStatusBitmap.seatCount(emptyList())).isEqualTo(0)
    }

    @Test
    fun `좌석 4개마다 바이트 하나를 쓴다`() {
        val rows = (0..4).map { row(it, SeatStatus.AVAILABLE) }

        assertThat(SeatStatusBitmap.encode(rows)).hasSize(2)
        assertThat(SeatStatusBitmap.seatCount(rows)).isEqualTo(5)
    }

    @Test
    fun `ordinal 0이 홀드면 첫 바이트의 상위 2비트가 01이다`() {
        val bytes = SeatStatusBitmap.encode(listOf(row(0, SeatStatus.HELD)))

        assertThat(byteAt(bytes, 0)).isEqualTo(0b01000000)
    }

    @Test
    fun `ordinal 3이 판매면 첫 바이트의 하위 2비트가 10이다`() {
        val bytes = SeatStatusBitmap.encode(listOf(row(3, SeatStatus.SOLD)))

        assertThat(byteAt(bytes, 0)).isEqualTo(0b00000010)
    }

    @Test
    fun `ordinal 4는 두 번째 바이트로 넘어간다`() {
        val bytes = SeatStatusBitmap.encode(listOf(row(4, SeatStatus.HELD)))

        assertThat(byteAt(bytes, 0)).isEqualTo(0)
        assertThat(byteAt(bytes, 1)).isEqualTo(0b01000000)
    }

    @Test
    fun `가능, 홀드, 판매, 가능 순서면 한 바이트가 00011000이다`() {
        val rows =
            listOf(
                row(0, SeatStatus.AVAILABLE),
                row(1, SeatStatus.HELD),
                row(2, SeatStatus.SOLD),
                row(3, SeatStatus.AVAILABLE),
            )

        assertThat(byteAt(SeatStatusBitmap.encode(rows), 0)).isEqualTo(0b00011000)
    }

    @Test
    fun `3만 석이면 정확히 7500바이트다`() {
        val rows = (0 until 30_000).map { row(it, SeatStatus.AVAILABLE) }

        assertThat(SeatStatusBitmap.encode(rows)).hasSize(7_500)
    }

    @Test
    fun `입력 순서가 달라도 같은 결과다`() {
        val rows = (0 until 100).map { row(it, SeatStatus.entries[it % 3]) }

        assertThat(SeatStatusBitmap.encode(rows.shuffled(Random(1)))).isEqualTo(SeatStatusBitmap.encode(rows))
    }

    @Test
    fun `인코딩한 비트맵을 디코딩하면 모든 좌석의 원래 상태가 나온다`() {
        val random = Random(42)
        val rows = (0 until 1_000).map { row(it, SeatStatus.entries[random.nextInt(SeatStatus.entries.size)]) }

        val bytes = SeatStatusBitmap.encode(rows)

        assertThat(rows).allSatisfy { assertThat(statusOf(bytes, it.ordinal)).isEqualTo(it.status) }
    }
}
