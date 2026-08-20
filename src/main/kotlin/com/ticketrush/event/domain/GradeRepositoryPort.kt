package com.ticketrush.event.domain

interface GradeRepositoryPort {
    fun save(grade: Grade): Grade
}
