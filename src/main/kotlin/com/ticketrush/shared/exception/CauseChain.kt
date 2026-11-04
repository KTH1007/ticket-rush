package com.ticketrush.shared.exception

private const val MAX_CAUSE_DEPTH = 10

// 원인 사슬을 바깥에서 안쪽 순서로 펼친다. 순환 참조에 갇히지 않게 깊이를 자른다
internal fun Throwable.causeChain(): List<Throwable> =
    generateSequence(this) { it.cause?.takeIf { next -> next !== it } }.take(MAX_CAUSE_DEPTH).toList()
