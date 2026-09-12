package com.ticketrush.reservation.infrastructure

import com.ticketrush.reservation.domain.Grade
import com.ticketrush.reservation.domain.GradeRepositoryPort
import org.springframework.stereotype.Repository

@Repository
class GradeRepositoryAdapter(
    private val jpaRepository: GradeJpaRepository,
) : GradeRepositoryPort {
    override fun save(grade: Grade): Grade = jpaRepository.saveAndFlush(grade)

    override fun findAllByEventId(eventId: Long): List<Grade> = jpaRepository.findAllByEventId(eventId)
}
