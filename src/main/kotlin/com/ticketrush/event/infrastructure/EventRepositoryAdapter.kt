package com.ticketrush.event.infrastructure

import com.ticketrush.event.domain.Event
import com.ticketrush.event.domain.EventRepositoryPort
import org.springframework.stereotype.Repository

@Repository
class EventRepositoryAdapter(
    private val jpaRepository: EventJpaRepository,
) : EventRepositoryPort {
    override fun save(event: Event): Event = jpaRepository.saveAndFlush(event)

    override fun findById(id: Long): Event? = jpaRepository.findById(id).orElse(null)
}
