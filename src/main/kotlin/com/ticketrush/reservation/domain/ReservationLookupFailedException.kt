package com.ticketrush.reservation.domain

import com.ticketrush.shared.exception.NotFoundException

// reservationNo가 없든 phone이 틀리든 구분하지 않고 이 예외 하나로 합친다.
// 예약 존재 여부 자체가 새어나가지 않게 하기 위함
class ReservationLookupFailedException :
    NotFoundException(
        code = "RESERVATION_LOOKUP_FAILED",
        message = "예매번호 또는 전화번호가 일치하는 예약이 없습니다",
    )
