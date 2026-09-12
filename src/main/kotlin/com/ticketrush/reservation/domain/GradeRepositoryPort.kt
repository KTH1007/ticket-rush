package com.ticketrush.reservation.domain

interface GradeRepositoryPort : GradeQueryPort {
    fun save(grade: Grade): Grade
}
