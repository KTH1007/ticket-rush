package com.ticketrush.event.infrastructure

import com.ticketrush.event.domain.Grade
import com.ticketrush.event.domain.GradeRepositoryPort
import org.springframework.stereotype.Repository

@Repository
class GradeRepositoryAdapter(
    private val jpaRepository: GradeJpaRepository,
) : GradeRepositoryPort {
    override fun save(grade: Grade): Grade = jpaRepository.saveAndFlush(grade)

    override fun findAllByEventId(eventId: Long): List<Grade> = jpaRepository.findAllByEventId(eventId)
}
