package com.ticketrush.reservation.domain

interface GradeQueryPort {
    fun findAllByEventId(eventId: Long): List<Grade>

    fun findAllByIds(gradeIds: List<Long>): List<Grade>
}
