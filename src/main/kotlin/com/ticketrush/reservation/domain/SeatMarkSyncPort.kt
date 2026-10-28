package com.ticketrush.reservation.domain

// DB 커밋이 끝난 뒤에 호출한다. 트랜잭션 안에서 부르면 Redis가 끝날 때까지 DB 연결을 쥔다
interface SeatMarkSyncPort {
    fun onSold(event: SeatsSoldEvent)

    fun onHoldShortened(event: SeatHoldShortenedEvent)

    fun onReturned(event: SeatsReturnedEvent)
}
