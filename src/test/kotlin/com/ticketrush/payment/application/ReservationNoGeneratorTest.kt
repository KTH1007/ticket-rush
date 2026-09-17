package com.ticketrush.payment.application

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class ReservationNoGeneratorTest {
    private val generator = ReservationNoGenerator()

    @Test
    fun `12자리 예매번호를 생성한다`() {
        // when
        val reservationNo = generator.generate()

        // then
        assertThat(reservationNo).hasSize(12)
    }

    @Test
    fun `호출할 때마다 다른 값을 생성한다`() {
        // when
        val generated = (1..1000).map { generator.generate() }

        // then
        assertThat(generated.toSet()).hasSize(1000)
    }
}
