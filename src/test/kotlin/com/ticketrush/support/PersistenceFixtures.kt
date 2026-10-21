package com.ticketrush.support

import jakarta.persistence.EntityManager

// 롤백되는 테스트는 flush 전에는 UPDATE가 DB로 나가지 않아 CHECK 제약이 평가되지 않는다.
// 바꾼 엔티티가 제약을 통과하는지 보려면 flush하고, clear해서 DB 값을 다시 읽는다
fun EntityManager.flushAndClear() {
    flush()
    clear()
}
