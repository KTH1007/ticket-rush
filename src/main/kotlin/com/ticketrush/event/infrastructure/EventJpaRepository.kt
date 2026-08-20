package com.ticketrush.event.infrastructure

import com.ticketrush.event.domain.Event
import org.springframework.data.jpa.repository.JpaRepository

interface EventJpaRepository : JpaRepository<Event, Long>
