package com.ticketrush.event.infrastructure

import com.ticketrush.event.domain.Event
import com.ticketrush.event.domain.EventRepositoryPort
import org.springframework.stereotype.Repository

@Repository
class EventRepositoryAdapter(
    private val jpaRepository: EventJpaRepository,
) : EventRepositoryPort {
    // saveAndFlush로 즉시 DB에 반영한다. flush는 JPA 세부사항이라 Port
    override fun save(event: Event): Event = jpaRepository.saveAndFlush(event)

    override fun findById(id: Long): Event = jpaRepository.findById(id).orElse(null)
}
