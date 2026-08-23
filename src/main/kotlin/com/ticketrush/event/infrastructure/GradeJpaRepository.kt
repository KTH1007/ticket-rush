package com.ticketrush.event.infrastructure

import com.ticketrush.event.domain.Grade
import org.springframework.data.jpa.repository.JpaRepository

interface GradeJpaRepository : JpaRepository<Grade, Long> {
    fun findAllByEventId(eventId: Long): List<Grade>
}
