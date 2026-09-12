package com.ticketrush.reservation.domain

interface GradeRepositoryPort {
    fun save(grade: Grade): Grade

    fun findAllByEventId(eventId: Long): List<Grade>
}
