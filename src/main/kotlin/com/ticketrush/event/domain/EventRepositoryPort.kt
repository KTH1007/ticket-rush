package com.ticketrush.event.domain

interface EventRepositoryPort : EventQueryPort {
    fun save(event: Event): Event
}
