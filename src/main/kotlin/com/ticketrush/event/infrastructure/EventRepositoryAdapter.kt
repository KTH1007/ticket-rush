package com.ticketrush.event.infrastructure

import com.ticketrush.event.domain.Event
import com.ticketrush.event.domain.EventRepositoryPort
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Repository

@Repository
class EventRepositoryAdapter(
    private val jpaRepository: EventJpaRepository,
) : EventRepositoryPort {
    override fun save(event: Event): Event = jpaRepository.saveAndFlush(event)

    override fun findById(id: Long): Event? = jpaRepository.findById(id).orElse(null)

    override fun findAll(pageable: Pageable): Page<Event> = jpaRepository.findAll(pageable)

    override fun existsById(id: Long): Boolean = jpaRepository.existsById(id)
}
