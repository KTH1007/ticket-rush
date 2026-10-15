package com.ticketrush.reservation.domain

import com.ticketrush.shared.exception.ConflictException

// 예약은 아직 HOLDING이지만 결제 호출 중에 만료될 수 있어 시작하지 않는다. 이미 만료된 경우(RESERVATION_NOT_HOLDING)와 구분해 좌석을 다시 고르게 안내한다
class ReservationHoldExpiringException :
    ConflictException(code = "RESERVATION_HOLD_EXPIRING", message = "홀드가 곧 만료돼 결제를 시작할 수 없습니다. 좌석을 다시 선택해주세요")
