package com.ticketrush.reservation.infrastructure

import com.ticketrush.reservation.domain.Grade
import org.springframework.data.jpa.repository.JpaRepository

interface GradeJpaRepository : JpaRepository<Grade, Long> {
    fun findAllByEventId(eventId: Long): List<Grade>
}
