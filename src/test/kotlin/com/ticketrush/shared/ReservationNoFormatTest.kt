package com.ticketrush.shared

import com.ticketrush.payment.application.ReservationNoGenerator
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

// 생성기와 검증 정규식이 어긋나면 정상 예매번호가 400으로 거절되거나, 생성기가 못 만드는 값이 통과한다
class ReservationNoFormatTest {
    private val format = Regex(ReservationNoFormat.PATTERN)

    @Test
    fun `생성기가 만든 예매번호는 항상 검증 형식을 만족하고 문자 32개를 모두 쓴다`() {
        val generator = ReservationNoGenerator()
        val seenChars = mutableSetOf<Char>()

        repeat(20_000) {
            val reservationNo = generator.generate()
            assertThat(reservationNo).matches(format.toPattern())
            seenChars += reservationNo.toSet()
        }

        // 정규식이 생성기의 문자 집합보다 좁으면 정상 번호가 거절되고, 넓으면 쓰지 않는 문자가 통과한다
        assertThat(seenChars).hasSize(32)
    }

    @Test
    fun `생성기가 쓰지 않는 문자나 길이는 거절한다`() {
        val invalid =
            listOf("", "abcdefgh2345", "ABCDEFGH234", "ABCDEFGH23456", "IBCDEFGH2345", "OBCDEFGH2345", "0BCDEFGH2345", "1BCDEFGH2345")

        invalid.forEach { assertThat(format.matches(it)).describedAs(it).isFalse() }
    }
}
