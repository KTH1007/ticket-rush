package com.ticketrush.event.domain

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

interface EventRepositoryPort {
    fun save(event: Event): Event

    fun findById(id: Long): Event?

    fun findAll(pageable: Pageable): Page<Event>

    fun existsById(id: Long): Boolean
}
