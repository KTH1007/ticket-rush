package com.ticketrush.reservation.domain

// 이미 결제됐거나(PAID) 취소/만료된(CANCELED/EXPIRED) 예약에 결제를 다시 시도할 때.
// 요청 자체는 유효하지만 현재 상태와 충돌하는 경우라 ConflictException(409).
import com.ticketrush.shared.exception.ConflictException

class ReservationNotHoldingException(
    status: ReservationStatus,
) : ConflictException(code = "RESERVATION_NOT_HOLDING", message = "결제를 진행할 수 없는 예약 상태입니다: $status")
