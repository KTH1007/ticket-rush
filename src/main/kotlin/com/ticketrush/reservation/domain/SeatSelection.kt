package com.ticketrush.reservation.domain

data class SeatSelection(
    val seatIds: List<Long>,
) {
    init {
        if (seatIds.size !in MIN_SIZE..MAX_SIZE) {
            throw InvalidSeatSelectionException("좌석은 ${MIN_SIZE}~${MAX_SIZE}개만 선택할 수 있습니다")
        }
        if (seatIds.toSet().size != seatIds.size) {
            throw InvalidSeatSelectionException("중복된 좌석 id는 선택할 수 없습니다")
        }
    }

    companion object {
        private const val MIN_SIZE = 1
        private const val MAX_SIZE = 2
    }
}
