package com.ticketrush

import org.springframework.boot.fromApplication
import org.springframework.boot.with

fun main(args: Array<String>) {
    fromApplication<TicketRushApplication>().with(TestcontainersConfiguration::class).run(*args)
}
