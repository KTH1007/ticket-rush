package com.ticketrush.event.domain

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

interface EventQueryPort {
    fun findById(id: Long): Event?

    fun findAll(pageable: Pageable): Page<Event>

    fun existsById(id: Long): Boolean
}
