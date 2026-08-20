package com.ticketrush.event.domain

interface EventRepositoryPort {
    fun save(event: Event): Event

    fun findById(id: Long): Event?
}
